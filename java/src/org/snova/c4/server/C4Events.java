/**
 * 
 */
package org.snova.c4.server;

import org.arch.event.Event;
import org.arch.event.EventDispatcher;
import org.arch.event.EventHandler;
import org.arch.event.EventSegment;
import org.arch.event.NamedEventHandler;
import org.arch.event.http.HTTPChunkEvent;
import org.arch.event.http.HTTPConnectionEvent;
import org.arch.event.http.HTTPErrorEvent;
import org.arch.event.http.HTTPRequestEvent;
import org.arch.event.http.HTTPResponseEvent;
import org.arch.event.misc.CompressEvent;
import org.arch.event.misc.CompressEventV2;
import org.arch.event.misc.EncryptEvent;
import org.arch.event.misc.EncryptEventV2;
import org.arch.event.socket.SocketCloseEvent;
import org.arch.event.socket.SocketConnectEvent;
import org.arch.event.socket.SocketDataEvent;
import org.snova.framework.event.SocketConnectionEvent;
import org.snova.framework.event.SocketReadEvent;
import org.snova.framework.event.TCPChunkEvent;
import org.snova.framework.event.UserLoginEvent;

/**
 * @author yinqiwen
 * 
 */
public class C4Events
{
	
	private static void registerEventHandler(Class<? extends Event> clazz,
	        EventHandler handler)
	{
		try
		{
			EventDispatcher.getSingletonInstance().register(clazz, handler);
		}
		catch (Exception e)
		{
			// TODO Auto-generated catch block
			
		}
	}
	
	public static void init(EventHandler handler, boolean isServer)
	{
		try
		{
			registerEventHandler(HTTPResponseEvent.class, handler);
			registerEventHandler(HTTPErrorEvent.class, handler);
			registerEventHandler(EventSegment.class, handler);
			
			registerEventHandler(CompressEvent.class, handler);
			registerEventHandler(EncryptEvent.class, handler);
			registerEventHandler(CompressEventV2.class, handler);
			registerEventHandler(EncryptEventV2.class, handler);
			
			registerEventHandler(SocketConnectionEvent.class, handler);
			registerEventHandler(UserLoginEvent.class, handler);
			registerEventHandler(TCPChunkEvent.class, handler);
			registerEventHandler(SocketReadEvent.class, handler);
			if (isServer)
			{
				EventDispatcher.getSingletonInstance().register(
				        HTTPRequestEvent.class, handler);
				EventDispatcher.getSingletonInstance().register(
				        HTTPChunkEvent.class, handler);
				EventDispatcher.getSingletonInstance().register(
				        HTTPConnectionEvent.class, handler);
				EventDispatcher.getSingletonInstance().register(
				        SocketConnectEvent.class, handler);
				EventDispatcher.getSingletonInstance().register(
						SocketDataEvent.class, handler);
				EventDispatcher.getSingletonInstance().register(
						SocketCloseEvent.class, handler);
			}
			else
			{
				if(null != handler && handler instanceof NamedEventHandler)
				{
					EventDispatcher.getSingletonInstance().registerNamedEventHandler((NamedEventHandler) handler);
				}
			}
			
		}
		catch (Exception e)
		{
			
		}
		
	}
}
