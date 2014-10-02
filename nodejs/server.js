
/**
 * New node file
 */
var http = require('http');
var url = require("url");
var fs = require('fs');
var net = require('net');
var HashMap = require('./hashmap.js').HashMap;
var ev = require('./event.js');

var VERSION="0.23.0";
var CACHE_LIMIT = 20;
var RESUME_WATER_MARK = CACHE_LIMIT/2;

var userSessionGroupMap = new HashMap();
var userEventQueueMap = new HashMap();
var userWriterMap = new HashMap();

function getWriter(user, index){
  if(!userWriterMap.has(user)){
     userWriterMap.set(user, new HashMap());
  }
  var ws = userWriterMap.get(user);
  if(!ws.has(index)){
     return null;
  }
  return ws.get(index);
}

function setWriter(user, index, writer){
  if(!userWriterMap.has(user)){
     userWriterMap.set(user, new HashMap());
  }
  var ws = userWriterMap.get(user);
  ws.set(index, writer);
}

function removeWriter(user, index){
  if(!userWriterMap.has(user)){
     userWriterMap.set(user, new HashMap());
  }
  var ws = userWriterMap.get(user);
  ws.remove(index);
}

function getEventQueue(user, index){
  if(!userEventQueueMap.has(user)){
     userEventQueueMap.set(user, new HashMap());
  }
  var eqs = userEventQueueMap.get(user);
  if(!eqs.has(index)){
    eqs.set(index, []);
  }
  return eqs.get(index);
}

function clearEventQueue(user, index){
  if(!userEventQueueMap.has(user)){
     userEventQueueMap.set(user, new HashMap());
  }
  var eqs = userEventQueueMap.get(user);
  eqs.set(index, []);
}

function getSession(user, index, sid){
  if(!userSessionGroupMap.has(user)){
     userSessionGroupMap.set(user, new HashMap());
  }
  var sessionGroup = userSessionGroupMap.get(user);
  if(!sessionGroup.has(index)){
    sessionGroup.set(index, new HashMap());
  }
  var sessionMap = sessionGroup.get(index);
  if(!sessionMap.has(sid))
  {
      sessionMap.set(sid, newSession(user, index, sid));
  }
  return sessionMap.get(sid);
}

function clearUser(user){
  if(userSessionGroupMap.has(user)){
     var sessionGroup = userSessionGroupMap.get(user);
     sessionGroup.forEach(function(sessionMap, index) {
        sessionMap.forEach(function(session, sid) {
           if(null != session && null != session.socket){
             session.socket.destroy();
           }
        });
        sessionMap.clear();
     });
     sessionGroup.clear();
     userSessionGroupMap.remove(user);
  }
  if(userEventQueueMap.has(user)){
     var eqs = userEventQueueMap.get(user);
     eqs.clear();
  }
}

function getSessionMap(user, index){
  if(!userSessionGroupMap.has(user)){
     userSessionGroupMap.set(user, new HashMap());
  }
  var sessionGroup = userSessionGroupMap.get(user);
  if(!sessionGroup.has(index)){
    sessionGroup.set(index, new HashMap());
  }
  return sessionGroup.get(index);
}

function encodeEvent(evv){
  if(evv.type == EVENT_TCP_CHUNK_TYPE){
    return ev.encodeChunkTcpChunkEvent(evv);
  }else if(evv.type == EVENT_TCP_CONNECTION_TYPE){
    return ev.encodeChunkConnectionEvent(evv);
  }else if(evv.type == HTTP_RESPONSE_EVENT_TYPE){
    return ev.encodeChunkResponseEvent(evv);
  }
}

function trySendCache(eq, writer){
  if(null == writer){
    return false;
  }
  while(eq.length > 0)
  {
      var data = eq.shift();
      if(!writer.write(data))
      {
        return false;
      }
   }
   return true;
}

function urlfetch(session, reqev){
 var hs = new Object();
 for(var i = 0; i < reqev.headers.length; i++){
    var hv = reqev.headers[i];
    if(null == hs[hv[0]]){
       hs[hv[0]] = hv[1];
    }else{
       var tmp = hs[hv[0]];
       hs[hv[0]] = [tmp, hv[1]];
    }
 }
 var options = {
    host: reqev.host,
    port: 80,
    path: reqev.url,
    method: reqev.method,
    headers:hs
 };
 var resev = ev.newEvent(HTTP_RESPONSE_EVENT_TYPE, 1, reqev.hash);
 var req = http.request(options, function(res) {
   resev.status = res.statusCode;
   resev.headers = [];
   if(resev.status == 302){
      if(null != reqev.rangeheader){
         resev.headers.push(["X-Range", reqev.rangeheader]);
      }
   }
  
   resev.headers.push(["X-Snova-HCE", "1"]);
   for (var hn in res.headers) {
      var hv = res.headers[hn];
      resev.headers.push([hn, hv]);
   }
   var _data= new Buffer(0);
   res.on('data', function(chunk){
      if(_data.length == 0){
        _data = chunk;
      }else{
        var len = _data.length + chunk.length;
        _data = ev.concatBuffers([_data, chunk],len);
      }
   });
   res.on('end', function(){
     resev.content = _data;
     session.responseProxyEvent(encodeEvent(resev));
   });
});
 
req.write(reqev.content);
req.end();
}

function resumeUserSessions(user, index, writer){
   var eq = getEventQueue(user, index);
   if(trySendCache(eq, writer)){
     clearEventQueue(user ,index);
     setWriter(user, index, writer);
   }else{
     removeWriter(user, index);
     return;
   }
   var sm = getSessionMap(user, index);
   sm.forEach(function(session, sid) {
     session.resume();
   });
}

function pauseUserSessions(user, index){
   var sm = getSessionMap(user, index);
   sm.forEach(function(session, sid) {
     session.pause();
   });
   removeWriter(user, index);
}

function newSession(user, index, hash){
   var obj = new Object();
   obj.remoteaddr = '';
   obj.sid = hash;
   obj.index = index;
   obj.user = user;
   obj.socket = null;
   obj.sequence = 0;
   obj.user = user;
   obj.paused = false;
   obj.close = function(passive) {
      if(null != obj.socket){
        obj.socket.destroy();
        obj.socket = null;
      }
      if(!passive){
        var closed = ev.newEvent(EVENT_TCP_CONNECTION_TYPE, 1, obj.sid);  
        closed.status =  TCP_CONN_CLOSED;
        closed.addr =  obj.remoteaddr;
        obj.responseProxyEvent(encodeEvent(closed));
      }

      var sm = getSessionMap(obj.user, obj.index);
      sm.remove(obj.sid);
   }

   obj.pause  = function(){
      if(null != obj.socket && !obj.paused){
        obj.socket.pause();
        obj.paused = true;
      }
   }
   obj.resume  = function(){
      if(null != obj.socket && obj.paused){
        obj.socket.resume();
        obj.paused = false;
      }
   }

  obj.responseProxyEvent  = function(data){
      var writer = getWriter(obj.user, obj.index);
      var eq = getEventQueue(obj.user, obj.index);
      if(null == writer){
         eq.push(data);
         //obj.pause();
         pauseUserSessions(obj.user, obj.index);
         return;
      }else{
         if(!writer.write(data)){
         	console.log('###Puases');
            pauseUserSessions(obj.user, obj.index);
         }  
      }
   }
   return obj;
}

function init()
{
   if(ev.getRC4Key().length == 0){
     var content = fs.readFileSync('./rc4.key');
      ev.setRC4Key(content.toString());
   }
}


function onIndex(request, response) {
    fs.readFile('./index.html', function (err, html) {
    if (err) {
        throw err; 
    }       
    response.writeHead(200, {"Content-Type": "text/html"});
    response.write(html.toString().replace("${version}",VERSION).replace("${version}",VERSION));
    response.end()
});
}


var HTTP_REQUEST_EVENT_TYPE=1000;
var HTTP_RESPONSE_EVENT_TYPE=1001;
var ENCRYPT_EVENT_TYPE=1501;
var EVENT_TCP_CONNECTION_TYPE = 12000;
var EVENT_USER_LOGIN_TYPE=12002;
var EVENT_TCP_CHUNK_TYPE=12001;
var EVENT_SOCKET_READ_TYPE=13000;

var TCP_CONN_OPENED  = 1;
var TCP_CONN_CLOSED  = 2;

var ENCRYPTER_NONE  = 0;
var ENCRYPTER_SE1  = 1;


function handleEvent(user, index, evv){
  var session = getSession(user, index, evv.hash);
  
  switch(evv.type){
    case EVENT_USER_LOGIN_TYPE:
    {
      clearUser(user);
      return;
    }
    case HTTP_REQUEST_EVENT_TYPE:
    {
      var host = evv.host;
      var port = 80;
      if(evv.method.toLowerCase() == 'connect'){
        port = 443;
      }else{
        if(evv.hashce){
          urlfetch(session, evv);
          return;
        }
      }
      var ss = host.split(":");
      if(ss.length == 2){
          host = ss[0];
          port = parseInt(ss[1]);
      }
      var remoteaddr = host+":" + port;
      if(null != session.socket && session.remoteaddr == remoteaddr){
        session.socket.write(evv.rawContent);
        return;
      }
      session.remoteaddr = remoteaddr;
      if(null != session.socket){
        session.socket.destroy();
      }
      
      var client = net.connect(port, host ,  function() { 
        if(evv.method.toLowerCase() == 'connect'){
          var established = ev.newEvent(EVENT_TCP_CHUNK_TYPE, 1, session.sid);
          established.seq = session.sequence++;
          established.content=new Buffer("HTTP/1.1 200 OK\r\n\r\n");
          session.responseProxyEvent(encodeEvent(established));
        }else{
          session.socket.write(evv.rawContent);
          //console.log("####writed:" + evv.rawContent.toString());
        }     
      });
      session.socket = client;
      session.resume();
      client.on('data', function(data) {
        var chunk = ev.newEvent(EVENT_TCP_CHUNK_TYPE, 1, session.sid);
        chunk.seq = session.sequence++;
        chunk.content = data;
        session.responseProxyEvent(encodeEvent(chunk));
      });
      client.on('error', function(err) {
        //console.log("####Failed to connect:" + remoteaddr + " :" + err);           
      });
      client.on('end', function(err) {
        client.destroy();         
      });
      client.on('close', function(had_error) {
        if(remoteaddr == (session.remoteaddr)){
            //console.log("####Close connection for " + remoteaddr);    
            session.close(false);        
        }
      });
      return;
    }
    case EVENT_TCP_CONNECTION_TYPE:
    {
      session.close(true);
      return;
    }
    case EVENT_TCP_CHUNK_TYPE:
    {
      if(null == session.socket){
        session.close(false);
        return;
      }
      session.socket.write(evv.content);
      return;
    }
    default:
    {
       console.log("################Unsupported type:" + evv.type);
       break;
     }
   }
  return;
}

function now(){
  return Math.round((new Date()).getTime()/ 1000);
}

function onInvoke(request, response) {
   var length = parseInt(request.headers['content-length']);
   var user = request.headers['usertoken'];
   var ispull = (url.parse(request.url).pathname == '/pull');
   var c4miscinfo = request.headers['c4miscinfo'];
   var rc4key = request.headers['rc4key'];
   if(rc4key != null){
      init();
      var tmp = new Buffer(rc4key, "base64");
      ev.rc4(ev.getRC4Key(), tmp);
      if(ev.getRC4Key() != tmp.toString()){
         response.writeHead(401, {"Connection":"close"});
         response.end();
         return;
      }
   }
   if(c4miscinfo != null){
    var miscInfo = c4miscinfo.split('_');
    var index = parseInt(miscInfo[0]);
    var timeout = parseInt(miscInfo[1]);
   }
   
   var postData = new Buffer(length);
   var recvlen = 0;

   if(ispull){
     response.writeHead(200, {"Content-Type": "image/jpeg", "C4LenHeader":1, "Connection":"keep-alive"});
   }else{
     response.writeHead(200, {"Content-Type": "image/jpeg", "C4LenHeader":1, "Connection":"keep-alive"});
     request.addListener("data", function(chunk) {
        chunk.copy(postData, recvlen, 0);
        recvlen += chunk.length;
     });
   }

   if(ispull){
       var transcEnded = false; 
       resumeUserSessions(user, index, response);
       setTimeout(function(){
         pauseUserSessions(user, index);
         //removeWriter(user, index);
         response.end();
         transcEnded = true;
       }, timeout*1000);
       var pingfunc = function () {
         if(!transcEnded){
            var closed = ev.newEvent(EVENT_TCP_CONNECTION_TYPE, 1, 0);  
            closed.status =  TCP_CONN_CLOSED;
            closed.addr =  "1";
            response.write(encodeEvent(closed));
            setTimeout(pingfunc, 10*1000);
         }
       };
      setTimeout(pingfunc, 10*1000);
      response.on('drain', function () {
         if(!transcEnded){
           resumeUserSessions(user, index, response);
         }
      });
      response.on('close', function () {
         pauseUserSessions(user, index);
         //removeWriter(user, index);
      });
   }
   
   request.addListener("end", function() {
      if(recvlen == length && length > 0){
        var readBuf = ev.newReadBuffer(postData);
        var events = ev.decodeEvents(readBuf);
        //console.log("Total events is "+events.length);
        for (var i = 0; i < events.length; i++)
        {
            handleEvent(user, index, events[i]);
        }
      }
      if(!ispull){
        response.end();
      }
   });
}

var handle = {}
handle["/"] = onIndex;
handle["/pull"] = onInvoke;
handle["/push"] = onInvoke;

function route(pathname, request, response) {
  if (typeof handle[pathname] === 'function') {
    handle[pathname](request, response);
  } else {
    response.writeHead(404, {"Content-Type": "text/plain"});
    response.end();
  }
}

function onRequest(request, response) {
  var pathname = url.parse(request.url).pathname;
  route(pathname, request, response)
}

var WebSocketServer = require("ws").Server
var ipaddr  = process.env.OPENSHIFT_NODEJS_IP || "0.0.0.0";
var port = process.env.VCAP_APP_PORT ||process.env.OPENSHIFT_NODEJS_PORT || process.env.PORT || 8080;
var server = http.createServer(onRequest);
server.listen(port, ipaddr);
var wss = new WebSocketServer({server: server})
console.log('Server running at '+ ipaddr + ":" + port);

var userConnBufTable = new HashMap();

function getUserConnBuffer(user, index){
  if(!userConnBufTable.has(user)){
     userConnBufTable.set(user, new HashMap());
  }
  var bufs = userConnBufTable.get(user);
  if(!bufs.has(index)){
    bufs.set(index, new Buffer(0));
  }
  return bufs.get(index);
}

wss.on("connection", function(ws) {
    var localConn = ws;
    var user = null;
    var index = null; 
    var rc4key = null;
    var handshaked = false;
    console.log("####New WS connection")
    var cumulateBuf = null;
    var chunkLen = -1;
    localConn.on("message", function(data, flags) {
      if(!handshaked)
      {
      	 init();
      	 var hs = eval('(' + data + ')');
      	 user = hs['UserToken'];
      	 index = hs['Index'];
      	 rc4key = hs['RC4Key'];
         handshaked = true;
         
         if(rc4key != null){
         	var tmp = new Buffer(rc4key, "base64");
            ev.rc4(ev.getRC4Key(), tmp);
            if(ev.getRC4Key() != tmp.toString()){
                localConn.send("Invalid RC4Key\r\n");
                localConn.close();
                return;
            }
         }
         

         localConn.send("OK\r\n");
         console.log('Websocket established with index:' + index);
         cumulateBuf = getUserConnBuffer(user, index);


         localConn.write = function(writeData) {
            localConn.send(writeData);
            return true;
         };
         resumeUserSessions(user, index, localConn);
         return;
      }
      
      //return
      if(null == cumulateBuf || cumulateBuf.length == 0){
        cumulateBuf = data;
      }else{
        cumulateBuf = ev.concatBuffers([cumulateBuf, data]);
      }
      for(;;){
        if(chunkLen == -1){
           if(cumulateBuf.length >= 4){
              var tmplen = cumulateBuf.length;
              chunkLen = cumulateBuf.readInt32BE(0);
              
              cumulateBuf = cumulateBuf.slice(4, tmplen);
           }else{
              return;
           }
        }
        if(chunkLen > 0){
          if(cumulateBuf.length >= chunkLen){
            var chunk = new Buffer(chunkLen);
            cumulateBuf.copy(chunk, 0, 0, chunkLen);
            var tmplen = cumulateBuf.length;
            cumulateBuf = cumulateBuf.slice(chunkLen, tmplen);
            chunkLen = -1;
            var readBuf = ev.newReadBuffer(chunk);
            var events = ev.decodeEvents(readBuf);
            for (var i = 0; i < events.length; i++)
            {
                handleEvent(user, index, events[i]);
            }
          }else{
            //console.log('#####chunkLen = ' + chunkLen);
            break;
          }
        }else{
          console.log('#####Invalid chunkLen = ' + chunkLen);
          cumulateBuf = new Buffer(0);
          break;
        }
      }
    })
    localConn.on("close", function() {
      pauseUserSessions(user, index);
      if(null != userConnBufTable.get(user)){
      	 userConnBufTable.get(user).set(index, cumulateBuf);
      }
    });
})


