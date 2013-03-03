/**
 * 
 */
package org.snova.c4.server.session.v3;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.arch.buffer.Buffer;
import org.arch.common.KeyValuePair;
import org.arch.event.Event;
import org.arch.event.TypeVersion;
import org.arch.event.http.HTTPEventContants;
import org.arch.event.http.HTTPRequestEvent;
import org.arch.event.http.HTTPResponseEvent;
import org.snova.framework.event.CommonEventConstants;
import org.snova.framework.event.SocketConnectionEvent;
import org.snova.framework.event.TCPChunkEvent;

/**
 * @author yinqiwen
 * 
 */
public class RemoteProxySession
{
	static ExecutorService	       globalThreadPool	  = Executors
	                                                          .newCachedThreadPool();
	String	                       user;
	int	                           groupIndex;
	int	                           sid;
	private int	                   sequence;
	private String	               remoteAddr;
	SocketChannel	               client	          = null;
	SelectionKey	               key;
	int	                           ops;
	private boolean	               isHttps;
	private boolean	               paused	          = false;
	private static ByteBuffer	   buffer	          = ByteBuffer
	                                                          .allocateDirect(65536);
	private byte[]	               httpRequestContent	= null;
	private Set<HttpURLConnection>	hcs	              = new HashSet<HttpURLConnection>();
	
	RemoteProxySessionManager	   sessionManager	  = null;
	
	public RemoteProxySession(RemoteProxySessionManager sessionManager,
	        String user, int groupIdx, int sid)
	{
		this.sessionManager = sessionManager;
		groupIndex = groupIdx;
		this.sid = sid;
		this.user = user;
	}
	
	void close()
	{
		doClose(key, client, remoteAddr, true);
	}
	
	protected static byte[] buildRequestContent(HTTPRequestEvent ev)
	{
		StringBuilder buffer = new StringBuilder();
		buffer.append(ev.method).append(" ").append(ev.url).append(" ")
		        .append("HTTP/1.1\r\n");
		for (KeyValuePair<String, String> header : ev.headers)
		{
			buffer.append(header.getName()).append(":")
			        .append(header.getValue()).append("\r\n");
		}
		buffer.append("\r\n");
		byte[] header = buffer.toString().getBytes();
		if (ev.content.readable())
		{
			byte[] all = new byte[header.length + ev.content.readableBytes()];
			System.arraycopy(header, 0, all, 0, header.length);
			ev.content.read(all, header.length, ev.content.readableBytes());
			return all;
		}
		else
		{
			return header;
		}
	}
	
	private boolean writeContent(byte[] content)
	{
		if (null != client)
		{
			try
			{
				int n = client.write(ByteBuffer.wrap(content));
				if (n < content.length)
				{
					System.out.println("###########Not finish write.");
				}
				return true;
			}
			catch (IOException e)
			{
				return false;
			}
		}
		return false;
	}
	
	boolean resume(boolean wakeSelector)
	{
		if (null == client)
		{
			return false;
		}
		if (!paused)
		{
			return false;
		}
		paused = false;
		sessionManager.addInvokcation(new Runnable()
		{
			public void run()
			{
				try
				{
					if (null != client)
					{
						ops = ops | SelectionKey.OP_READ;
						key = client.register(sessionManager.selector, ops,
						        new SessionAddressPair(RemoteProxySession.this,
						                remoteAddr));
						paused = false;
					}
				}
				catch (ClosedChannelException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
					close();
				}
			}
		}, wakeSelector);
		return true;
	}
	
	boolean pause(boolean wakeSelector)
	{
		if (null == client)
		{
			return false;
		}
		if (paused)
		{
			return false;
		}
		paused = true;
		sessionManager.addInvokcation(new Runnable()
		{
			public void run()
			{
				try
				{
					if (null != client)
					{
						ops = ops & ~SelectionKey.OP_READ;
						key = client.register(sessionManager.selector, ops,
						        new SessionAddressPair(RemoteProxySession.this,
						                remoteAddr));
						paused = true;
					}
				}
				catch (ClosedChannelException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
					close();
				}
			}
		}, wakeSelector);
		return true;
	}
	
	private HTTPResponseEvent urlfetch(HTTPRequestEvent req)
	{
		HTTPResponseEvent res = new HTTPResponseEvent();
		res.setHash(req.getHash());
		HttpURLConnection.setFollowRedirects(false);
		HttpURLConnection conn = null;
		try
		{
			URL url = new URL("http://" + req.getHeader("Host") + req.url);
			conn = (HttpURLConnection) url.openConnection();
			
			conn.setDoInput(true);
			conn.setRequestMethod(req.method);
			for (KeyValuePair<String, String> h : req.headers)
			{
				conn.addRequestProperty(h.getName(), h.getValue());
			}
			if (req.content.readable())
			{
				conn.setDoOutput(true);
			}
			conn.connect();
			hcs.add(conn);
			if (req.content.readable())
			{
				conn.getOutputStream()
				        .write(req.content.getRawBuffer(),
				                req.content.getReadIndex(),
				                req.content.readableBytes());
			}
			
			res.statusCode = conn.getResponseCode();
			if (res.statusCode == 302)
			{
				if(req.getHeader("Range") != null)
				{
					res.setHeader("X-Range", req.getHeader("Range"));
				}
			}
			Map<String, List<String>> rh = conn.getHeaderFields();
			for (String name : rh.keySet())
			{
				if (null != name && name.length() > 0)
				{
					List<String> vs = rh.get(name);
					for (String v : vs)
					{
						res.addHeader(name, v);
					}
				}
			}
			Buffer buffer = new Buffer(4096);
			byte[] tmp = new byte[65536];
			while (true)
			{
				try
				{
					int n = conn.getInputStream().read(tmp);
					if (n > 0)
					{
						buffer.write(tmp, 0, n);
					}
					else
					{
						break;
					}
				}
				catch (Exception e)
				{
					break;
				}
			}
			res.setHeader("X-Snova-HCE", "1");
			res.content = buffer;
			sessionManager.offerReadyEvent(user, groupIndex, res);
			return res;
		}
		catch (Exception e)
		{
			res.statusCode = 503;
			sessionManager.offerReadyEvent(user, groupIndex, res);
			e.printStackTrace();
			return null;
		}
		finally
		{
			if (null != conn)
			{
				hcs.remove(conn);
			}
		}
	}
	
	boolean handleEvent(TypeVersion tv, Event ev)
	{
		switch (tv.type)
		{
			case CommonEventConstants.EVENT_TCP_CHUNK_TYPE:
			{
				// System.out.println("#####recv chunk for " + remoteAddr);
				if (null != client)
				{
					final TCPChunkEvent chunk = (TCPChunkEvent) ev;
					return writeContent(chunk.content);
				}
				else
				{
					return false;
				}
			}
			case CommonEventConstants.EVENT_TCP_CONNECTION_TYPE:
			{
				SocketConnectionEvent event = (SocketConnectionEvent) ev;
				// if (event.status == SocketConnectionEvent.TCP_CONN_CLOSED)
				{
					doClose(key, client, remoteAddr, false);
					sessionManager.removeSession(this);
				}
				break;
			}
			case HTTPEventContants.HTTP_REQUEST_EVENT_TYPE:
			{
				final HTTPRequestEvent req = (HTTPRequestEvent) ev;
				String host = req.getHeader("Host");
				int port = 80;
				if (host.indexOf(":") != -1)
				{
					String[] ss = host.split(":");
					host = ss[0];
					port = Integer.parseInt(ss[1]);
				}
				else
				{
					if (req.method.equalsIgnoreCase("Connect"))
					{
						port = 443;
					}
				}
				if (req.method.equalsIgnoreCase("Connect"))
				{
					isHttps = true;
				}
				else
				{
					if (req.getHeader("X-Snova-HCE") != null)
					{
						globalThreadPool.submit(new Runnable()
						{
							public void run()
							{
								urlfetch(req);
							}
						});
						return true;
					}
					else
					{
						httpRequestContent = buildRequestContent(req);
					}
					
				}
				sequence = 0;
				if (checkClient(host, port))
				{
					if (null != httpRequestContent)
					{
						writeContent(httpRequestContent);
						httpRequestContent = null;
					}
				}
				break;
			}
			default:
			{
				// logger.error("Unsupported event type version " + tv.type +
				// ":"
				// + tv.version);
				return false;
			}
		}
		return true;
	}
	
	void onConnected()
	{
		SocketChannel socketChannel = (SocketChannel) key.channel();
		try
		{
			if (socketChannel.isConnectionPending())
			{
				socketChannel.finishConnect();
			}
			ops = SelectionKey.OP_READ;
			paused = false;
			key = socketChannel.register(sessionManager.selector, ops,
			        new SessionAddressPair(this, remoteAddr));
			if (isHttps)
			{
				TCPChunkEvent chunk = new TCPChunkEvent();
				chunk.sequence = sequence;
				sequence++;
				chunk.setHash(sid);
				chunk.content = "HTTP/1.1 200 OK\r\n\r\n".getBytes();
				sessionManager.offerReadyEvent(user, groupIndex, chunk);
			}
			else
			{
				writeContent(httpRequestContent);
				httpRequestContent = null;
			}
		}
		catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			if (isHttps)
			{
				TCPChunkEvent chunk = new TCPChunkEvent();
				chunk.sequence = sequence;
				sequence++;
				chunk.setHash(sid);
				chunk.content = "HTTP/1.1 503 Service Unavailable\r\n\r\n"
				        .getBytes();
				sessionManager.offerReadyEvent(user, groupIndex, chunk);
			}
			doClose(key, client, remoteAddr, true);
		}
	}
	
	void onRead(SelectionKey key, String address)
	{
		SocketChannel socketChannel = (SocketChannel) key.channel();
		try
		{
			while (true)
			{
				if (client == null || client != socketChannel)
				{
					key.cancel();
					return;
				}
				buffer.clear();
				int n = socketChannel.read(buffer);
				// System.out.println("#####" + remoteAddr + " onread:" + n);
				if (n < 0)
				{
					doClose(key, socketChannel, address, true);
				}
				else if (n > 0)
				{
					buffer.flip();
					TCPChunkEvent chunk = new TCPChunkEvent();
					chunk.sequence = sequence;
					sequence++;
					chunk.setHash(sid);
					chunk.content = new byte[n];
					buffer.get(chunk.content);
					if (!sessionManager
					        .offerReadyEvent(user, groupIndex, chunk))
					{
						pause(true);
					}
					if (n < buffer.capacity())
					{
						break;
					}
				}
				else
				{
					break;
				}
			}
			
		}
		catch (Exception e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("#####Close " + address);
			doClose(key, socketChannel, address, true);
		}
	}
	
	private void doClose(SelectionKey key, SocketChannel channel,
	        String address, boolean notify)
	{
		if (null == address)
		{
			return;
		}
		if (null != channel)
		{
			try
			{
				channel.close();
				key.cancel();
			}
			catch (IOException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if (address.equals(remoteAddr))
		{
			client = null;
			sessionManager.removeSession(this);
			if (notify)
			{
				SocketConnectionEvent closeEv = new SocketConnectionEvent();
				closeEv.setHash(sid);
				closeEv.addr = remoteAddr;
				closeEv.status = SocketConnectionEvent.TCP_CONN_CLOSED;
				closeEv.setAttachment(Boolean.TRUE);
				sessionManager.offerReadyEvent(user, groupIndex, closeEv);
			}
			for (HttpURLConnection c : hcs)
			{
				c.disconnect();
			}
			hcs.clear();
		}
	}
	
	private boolean checkClient(String host, int port)
	{
		String addr = host + ":" + port;
		if (addr.equals(remoteAddr) && null != client && client.isConnected())
		{
			resume(true);
			return true;
		}
		String oldAddr = remoteAddr;
		remoteAddr = addr;
		doClose(key, client, oldAddr, true);
		try
		{
			paused = false;
			remoteAddr = addr;
			client = SocketChannel.open();
			client.configureBlocking(false);
			client.connect(new InetSocketAddress(host, port));
			sessionManager.addInvokcation(new Runnable()
			{
				public void run()
				{
					try
					{
						ops = SelectionKey.OP_CONNECT;
						key = client.register(sessionManager.selector, ops,
						        new SessionAddressPair(RemoteProxySession.this,
						                remoteAddr));
					}
					catch (ClosedChannelException e)
					{
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}, true);
		}
		catch (IOException e)
		{
			e.printStackTrace();
			return false;
		}
		return false;
	}
}
