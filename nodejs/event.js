

//exports.HTTP_REQUEST_EVENT_TYPE=1000;
//exports.ENCRYPT_EVENT_TYPE=1501;
//exports.EVENT_USER_LOGIN_TYPE=12002;
//exports.EVENT_TCP_CHUNK_TYPE=12001;
//exports.EVENT_SOCKET_READ_TYPE=13000;


exports.newReadBuffer = newReadBuffer;

exports.newWriteBuffer = newWriteBuffer;

exports.newEvent = newEvent;

exports.setRC4Key = setRC4Key;

exports.getRC4Key = getRC4Key;

exports.rc4 = rc4;

function newEvent(type, version, hash){
   var obj = new Object();
   obj.type = type;
   obj.version = version;
   obj.hash = hash;
   return obj;
}

var RC4Key= "";

function setRC4Key(key){
   RC4Key = key;
}

function getRC4Key(){
   return RC4Key;
}

function newReadBuffer(data){
  var obj = new Object();
   obj.readIdx = 0;
   obj.raw = data;
   return obj;
}

function newWriteBuffer(data){
  var obj = new Object();
   obj.writeIdx = 0;
   obj.raw = data;
   return obj;
}

exports.encodeChunkTcpChunkEvent = function(ev){
   var buffer = newWriteBuffer(new Buffer(24));
   writeUvarint(buffer, ev.type);
   writeUvarint(buffer, ev.version);
   writeUvarint(buffer, ev.hash);
   writeUvarint(buffer, ev.seq);
   writeUvarint(buffer, ev.content.length);
   //writeBytes(buffer,ev.content);
   //var tmp = buffer.raw.slice(0, buffer.writeIdx);
   var header = buffer.raw.slice(0, buffer.writeIdx);
   var len = header.length + ev.content.length;
   var tmp = Buffer.concat([header, ev.content],len);
   return encodeEncryptChunk(tmp, ev.hash);
}

exports.encodeChunkResponseEvent = function(ev){
   var buffer = newWriteBuffer(new Buffer(4096));
   writeUvarint(buffer, ev.type);
   writeUvarint(buffer, ev.version);
   writeUvarint(buffer, ev.hash);
   writeUvarint(buffer, ev.status);
   writeUvarint(buffer, ev.headers.length);
   for(var i = 0; i < ev.headers.length; i++){
     var hv = ev.headers[i];
     writeString(buffer, hv[0]);
     writeString(buffer, hv[1]);
   }
    writeUvarint(buffer, ev.content.length);
   var header = buffer.raw.slice(0, buffer.writeIdx);
   var len = header.length + ev.content.length;
   var tmp = Buffer.concat([header, ev.content],len);
   return encodeEncryptChunk(tmp, ev.hash);
}

exports.encodeChunkConnectionEvent = function(ev){
   var buffer = newWriteBuffer(new Buffer(ev.addr.length + 24));
   writeUvarint(buffer, ev.type);
   writeUvarint(buffer,ev.version);
   writeUvarint(buffer,ev.hash);
   writeUvarint(buffer,ev.status);
   writeString(buffer,ev.addr);
   return encodeEncryptChunk(buffer.raw.slice(0, buffer.writeIdx), ev.hash);
}

function encodeEncryptChunk(data, hash){
  var buffer = newWriteBuffer(new Buffer(data.length + 24));
  buffer.raw.writeInt32BE(1, 0);
  buffer.writeIdx+= 4;
  writeUvarint(buffer,ENCRYPT_EVENT_TYPE);
  writeUvarint(buffer,2);
  writeUvarint(buffer,hash);
  if(RC4Key.length == 0){
    writeUvarint(buffer,ENCRYPTER_SE1);
  }else{
    writeUvarint(buffer,ENCRYPTER_RC4);
  }
  writeUvarint(buffer, data.length);
  if(RC4Key.length == 0){
    for(var i = 0; i < data.length; i++){
       var k = data.readUInt8(i);
       k -= 1;
       if(k < 0) {
         k += 256;
       }
       data.writeUInt8(k, i);
       //buffer.raw.writeUInt8(k, buffer.writeIdx++);
    }
  }else{
    rc4(RC4Key, data);
    //data.copy(buffer.raw, buffer.writeIdx, 0, data.length);
    //buffer.writeIdx += data.length;
  }
  buffer.raw.writeInt32BE(buffer.writeIdx - 4 + data.length, 0);
  var header =  buffer.raw.slice(0, buffer.writeIdx);
  var len = header.length + data.length;
  //console.log("#########chunk len is " + (buffer.writeIdx - 4) + ", buffer lenth=" + tmp.length);
  return Buffer.concat([header, data], len);
}

function writeUvarint(data, n) {
  //console.log("#########chunk len is " + n);

 for (var i = 0; i==0 || (n && i < 5); i++) {
      var byt = n%128;
      n >>>= 7;
      if (n) {
          byt += 128;
      }
     data.raw.writeUInt8(byt, data.writeIdx++);
  }
  
}

function readUvarint(data){
  var n = 0;
  var endloop = false;
  var offset=1;
  for (var i = 0; !endloop && i < 5; i++) {
      var byt = data.raw.readUInt8(data.readIdx++);
      if (byt === undefined) {
          console.log("read undefined byte from stream: n is "+n);
          break;
      }
      if (byt < 128) {
          endloop = true;
      }
      n += offset*(byt&(i==4?15:127));
      offset *= 128;
  }
  return n;
}

function writeBytes(buffer, data){
  var len = data.length;
  writeUvarint(buffer, len);
  data.copy(buffer.raw, buffer.writeIdx);
  buffer.writeIdx += len;
}

function writeString(buffer, str){
  var len = str.length;
  writeUvarint(buffer, len);
  buffer.raw.write(str, buffer.writeIdx);
  buffer.writeIdx += len;
}

function readString(data){
  var len = readUvarint(data);
  var tmp = data.raw.slice(data.readIdx, data.readIdx+len);
  data.readIdx += len;
  return tmp.toString();
}

function readBytes(data){
  var len = readUvarint(data);
  var content = data.raw.slice(data.readIdx, data.readIdx+len);
  data.readIdx += len;
  return content;
}

var HTTP_REQUEST_EVENT_TYPE=1000;
var ENCRYPT_EVENT_TYPE=1501;
var EVENT_TCP_CONNECTION_TYPE = 12000;
var EVENT_USER_LOGIN_TYPE=12002;
var EVENT_TCP_CHUNK_TYPE=12001;
var EVENT_SOCKET_READ_TYPE=13000;

var TCP_CONN_OPENED  = 1;
var TCP_CONN_CLOSED  = 2;

var ENCRYPTER_NONE  = 0;
var ENCRYPTER_SE1  = 1;
var ENCRYPTER_RC4  = 2;

function rc4(key, content) {
  var s = [], j = 0, x;
  for (var i = 0; i < 256; i++) {
    s[i] = i;
  }
  for (i = 0; i < 256; i++) {
    j = (j + s[i] + key.charCodeAt(i % key.length)) % 256;
    x = s[i];
    s[i] = s[j];
    s[j] = x;
  }
  i = 0;
  j = 0;
  for (var y = 0; y < content.length; y++) {
    i = (i + 1) % 256;
    j = (j + s[i]) % 256;
    x = s[i];
    s[i] = s[j];
    s[j] = x;
    var tmp = content.readUInt8(y) ^ s[(s[i] + s[j]) % 256];
    content.writeUInt8(tmp, y);
  }
}


function decodeRawEvent(type, version, data){
  var ev=newEvent(type, version, 0);
  switch(type)
  {
   case EVENT_USER_LOGIN_TYPE:
     ev.user = readString(data);
     break;
   case EVENT_TCP_CONNECTION_TYPE:
     ev.status = readUvarint(data);
     ev.addr = readString(data);
     break
   case EVENT_SOCKET_READ_TYPE:
     ev.timeout = readUvarint(data);
     ev.maxRead = readUvarint(data);
     break;
   case EVENT_TCP_CHUNK_TYPE:
     ev.seq = readUvarint(data);
     ev.content = readBytes(data);
     break;
   case ENCRYPT_EVENT_TYPE:
     var encType = readUvarint(data);
     //var len = readUvarint(data);
     if(encType == ENCRYPTER_SE1){
       var decrytContent = readBytes(data);
       for(var i = 0; i < decrytContent.length; i++){
          var k = decrytContent.readUInt8(i);
          k += 1;
          if(k >= 256) {
            k -= 256;
          }
          decrytContent.writeUInt8(k,i);
       }
       return decodeEvent(newReadBuffer(decrytContent));
     }else if(encType == ENCRYPTER_RC4){
       var decrytContent = readBytes(data);
       rc4(RC4Key, decrytContent);
       return decodeEvent(newReadBuffer(decrytContent));
     }else if(encType == ENCRYPTER_NONE){
        readUvarint(data);
        return decodeEvent(data);
     }
     break;
   case HTTP_REQUEST_EVENT_TYPE:
     ev.url = readString(data);
     ev.method = readString(data);
     var headlen =  readUvarint(data);
     ev.headers = [];
     ev.hashce = false;
     ev.rangeheader = null;
     var headerstr = ev.method + " " + ev.url + " HTTP/1.1\r\n";
     for(var i = 0; i < headlen; i++){
          var name = readString(data);
          var value = readString(data);
          if(name.toLowerCase() == "host"){
            ev.host = value;
          }else if(name.toLowerCase() == "x-snova-hce"){
            ev.hashce = true;
          }else if(name.toLowerCase() == "range"){
             ev.rangeheader = value;
          }
          ev.headers.push([name, value]);
          headerstr = headerstr + name + ":" + value + "\r\n";
     }
     headerstr = headerstr + "\r\n";
     ev.content = readBytes(data);
     if(ev.method.toLowerCase() != "connect"){
        var rawContent = new Buffer(headerstr.length + ev.content.length);
        rawContent.write(headerstr);
        ev.content.copy(rawContent,headerstr.length);
        ev.rawContent = rawContent;
     }
     break;
   default:

   }
   return ev;
}

exports.decodeEvent = decodeEvent;

function decodeEvent(data){
  var type = readUvarint(data);
  var version = readUvarint(data);
  var hash = readUvarint(data);
  var ev = decodeRawEvent(type, version, data);
  ev.hash = hash;
  return ev;
}


exports.decodeEvents = function(data){
  var evs=[];
  while (data.readIdx < data.raw.length)
  {
     var ev = decodeEvent(data);
     evs.push(ev);
  }
  return evs;
}

