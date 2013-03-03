/**
 * 
 */
package org.snova.c4.server.session.v3;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import org.arch.buffer.Buffer;
import org.arch.event.Event;
import org.arch.event.EventDispatcher;
import org.arch.event.TypeVersion;
import org.arch.event.http.HTTPEventContants;
import org.arch.event.misc.EncryptEventV2;
import org.arch.event.misc.EncryptType;
import org.snova.c4.server.C4Events;
import org.snova.framework.event.CommonEventConstants;
import org.snova.framework.event.SocketConnectionEvent;
import org.snova.framework.event.UserLoginEvent;

/**
 * @author yinqiwen
 * 
 */
public class RemoteProxySessionManager implements Runnable
{
	
	public static final int	         MAX_QUEUE_EVENTS	 = 10;
	Selector	                     selector;
	
	private Map	                     userSessionGroup	 = new HashMap();
	private Map	                     userReadyEventQueue	= new HashMap();
	
	private LinkedList<Runnable>	 invokations	     = new LinkedList<Runnable>();
	static RemoteProxySessionManager	instance	     = new RemoteProxySessionManager();
	
	private RemoteProxySessionManager()
	{
		try
		{
			C4Events.init(null, true);
			selector = Selector.open();
			new Thread(this).start();
		}
		catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private void processRegisteTask()
	{
		synchronized (invokations)
		{
			while (!invokations.isEmpty())
			{
				Runnable task = invokations.removeFirst();
				try
				{
					task.run();
				}
				catch (Exception e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
	
	public static RemoteProxySessionManager getInstance()
	{
		return instance;
	}
	
	void removeSession(RemoteProxySession session)
	{
		synchronized (userSessionGroup)
		{
			HashMap sessionGroup = (HashMap) userSessionGroup.get(session.user);
			if (null != sessionGroup)
			{
				HashMap sessionMap = (HashMap) sessionGroup
				        .get(session.groupIndex);
				if (null != sessionMap)
				{
					sessionMap.remove(session.sid);
				}
			}
		}
	}
	
	public synchronized LinkedList<Event> getEventQueue(String user,
	        int groupIdx)
	{
		HashMap eqs = (HashMap) userReadyEventQueue.get(user);
		if (null == eqs)
		{
			eqs = new HashMap();
			userReadyEventQueue.put(user, eqs);
		}
		LinkedList<Event> queue = (LinkedList<Event>) eqs.get(groupIdx);
		if (null == queue)
		{
			queue = new LinkedList<Event>();
			eqs.put(groupIdx, queue);
		}
		return queue;
	}
	
	boolean offerReadyEvent(String user, int groupIdx, Event ev)
	{
		EncryptEventV2 encrypt = new EncryptEventV2();
		encrypt.type = EncryptType.RC4;
		encrypt.ev = ev;
		encrypt.setHash(ev.getHash());
		encrypt.setAttachment(ev.getAttachment());
		LinkedList<Event> queue = getEventQueue(user, groupIdx);
		if (queue.size() <= (MAX_QUEUE_EVENTS / 2))
		{
			resumeSessions(user, groupIdx);
		}
		synchronized (queue)
		{
			queue.add(encrypt);
			queue.notify();
			return queue.size() <= MAX_QUEUE_EVENTS;
		}
	}
	
	public Event consumeReadyEvent(String user, int groupIndex, Buffer buf,
	        long timeout)
	{
		LinkedList<Event> queue = getEventQueue(user, groupIndex);
		Event ev = null;
		synchronized (queue)
		{
			if (queue.size() <= (MAX_QUEUE_EVENTS / 2))
			{
				resumeSessions(user, groupIndex);
			}
			if (queue.isEmpty() && timeout > 0)
			{
				try
				{
					queue.wait(timeout);
				}
				catch (InterruptedException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (!queue.isEmpty())
			{
				ev = queue.removeFirst();
			}
			if (queue.size() <= (MAX_QUEUE_EVENTS / 2))
			{
				resumeSessions(user, groupIndex);
			}
		}
		if (null != ev)
		{
			Object attach = ev.getAttachment();
			if (null == attach && !sessionExist(user, groupIndex, ev.getHash()))
			{
				// System.out.println("####Session:" + ev.getHash()
				// + " is not exist!");
				ev = null;
			}
		}
		if (null == ev)
		{
			//As fake ping package
			SocketConnectionEvent closeEv = new SocketConnectionEvent();
			closeEv.setHash(0);
			closeEv.status = SocketConnectionEvent.TCP_CONN_CLOSED;
			ev = closeEv;
		}
		// else
		{
			ev.encode(buf);
		}
		
		return ev;
	}
	
	public void resumeSessions(String user, int groupIdx)
	{
		boolean wake = false;
		synchronized (userSessionGroup)
		{
			HashMap sessionGroup = (HashMap) userSessionGroup.get(user);
			if (null != sessionGroup)
			{
				HashMap sessionMap = (HashMap) sessionGroup.get(groupIdx);
				if (null != sessionMap)
				{
					for (Object key : sessionMap.keySet())
					{
						RemoteProxySession session = (RemoteProxySession) sessionMap
						        .get(key);
						if (session.resume(false))
						{
							wake = true;
						}
					}
				}
			}
		}
		if (wake)
		{
			selector.wakeup();
		}
		
	}
	
	public void pauseSessions(String user, int groupIdx)
	{
		boolean wake = false;
		synchronized (userSessionGroup)
		{
			HashMap sessionGroup = (HashMap) userSessionGroup.get(user);
			if (null != sessionGroup)
			{
				HashMap sessionMap = (HashMap) sessionGroup.get(groupIdx);
				if (null != sessionMap)
				{
					for (Object key : sessionMap.keySet())
					{
						RemoteProxySession session = (RemoteProxySession) sessionMap
						        .get(key);
						
						if (session.pause(false))
						{
							wake = true;
						}
					}
				}
			}
		}
		if (wake)
		{
			selector.wakeup();
		}
	}
	
	void addInvokcation(Runnable invoke, boolean wakeSelector)
	{
		synchronized (invokations)
		{
			invokations.add(invoke);
			if (wakeSelector)
			{
				selector.wakeup();
			}
		}
	}
	
	public boolean sessionExist(String user, int groupIdx, int sid)
	{
		synchronized (userSessionGroup)
		{
			HashMap sessionGroup = (HashMap) userSessionGroup.get(user);
			if (null == sessionGroup)
			{
				return false;
			}
			HashMap sessionMap = (HashMap) sessionGroup.get(groupIdx);
			if (null == sessionMap)
			{
				return false;
			}
			return sessionMap.containsKey(sid);
		}
	}
	
	RemoteProxySession getSession(String user, int groupIdx, int sid,
	        boolean create)
	{
		synchronized (userSessionGroup)
		{
			HashMap sessionGroup = (HashMap) userSessionGroup.get(user);
			if (null == sessionGroup)
			{
				sessionGroup = new HashMap();
				userSessionGroup.put(user, sessionGroup);
			}
			HashMap sessionMap = (HashMap) sessionGroup.get(groupIdx);
			if (null == sessionMap)
			{
				sessionMap = new HashMap();
				sessionGroup.put(groupIdx, sessionMap);
			}
			RemoteProxySession session = (RemoteProxySession) sessionMap
			        .get(sid);
			if (null == session)
			{
				if (create)
				{
					session = new RemoteProxySession(this, user, groupIdx, sid);
					sessionMap.put(sid, session);
				}
			}
			return session;
		}
	}
	
	private void clearUser(String user)
	{
		HashMap sessionGroup = (HashMap) userSessionGroup.remove(user);
		if (null != sessionGroup)
		{
			for (Object key : sessionGroup.keySet())
			{
				HashMap ss = (HashMap) sessionGroup.get(key);
				if (null != ss)
				{
					for (Object sid : ss.keySet())
					{
						RemoteProxySession session = (RemoteProxySession) ss
						        .get(sid);
						if (null != session)
						{
							session.close();
						}
					}
				}
			}
		}
		HashMap eqs = (HashMap) userReadyEventQueue.get(user);
		if (null != eqs)
		{
			for (Object key : eqs.keySet())
			{
				LinkedList<Event> queue = (LinkedList<Event>) eqs.get(key);
				queue.clear();
			}
			eqs.clear();
		}
	}
	
	public void dispatchEvent(String user, int groupIdx, final Buffer content)
	        throws Exception
	{
		while (content.readable())
		{
			Event event = EventDispatcher.getSingletonInstance().parse(content);
			event = Event.extractEvent(event);
			TypeVersion tv = Event.getTypeVersion(event.getClass());
			if (tv.type == CommonEventConstants.EVENT_USER_LOGIN_TYPE)
			{
				UserLoginEvent usev = (UserLoginEvent) event;
				clearUser(usev.user);
			}
			else if (tv.type == CommonEventConstants.EVENT_TCP_CONNECTION_TYPE)
			{
				RemoteProxySession s = getSession(user, groupIdx,
				        event.getHash(), false);
				if (null != s)
				{
					s.close();
					System.out.println("####Remove Session:" + event.getHash());
				}
			}
			else if (tv.type == HTTPEventContants.HTTP_REQUEST_EVENT_TYPE)
			{
				RemoteProxySession s = getSession(user, groupIdx,
				        event.getHash(), true);
				s.handleEvent(tv, event);
			}
			else
			{
				RemoteProxySession s = getSession(user, groupIdx,
				        event.getHash(), false);
				if (s != null)
				{
					s.handleEvent(tv, event);
				}
			}
		}
	}
	
	@Override
	public void run()
	{
		while (true)
		{
			try
			{
				processRegisteTask();
				int n = selector.select();
				if (n > 0)
				{
					Iterator keys = this.selector.selectedKeys().iterator();
					while (keys.hasNext())
					{
						SelectionKey key = (SelectionKey) keys.next();
						keys.remove();
						SessionAddressPair pair = (SessionAddressPair) key
						        .attachment();
						pair.session.key = key;
						if (!key.isValid())
						{
							continue;
						}
						try
						{
							if (key.isConnectable())
							{
								pair.session.onConnected();
							}
							else if (key.isReadable())
							{
								pair.session.onRead(key, pair.address);
							}
						}
						catch (Exception e)
						{
							e.printStackTrace();
						}
						
					}
				}
			}
			catch (IOException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
}
