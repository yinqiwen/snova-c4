/**
 * 
 */
package org.snova.c4.server.servlet;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedList;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.arch.buffer.Buffer;
import org.arch.buffer.BufferHelper;
import org.arch.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snova.c4.server.session.v3.RemoteProxySessionManager;

/**
 * @author wqy
 * 
 */
public class PullServlet extends HttpServlet
{
	protected Logger	logger	= LoggerFactory.getLogger(getClass());
	
	private void flushContent(HttpServletResponse resp, Buffer buf)
	        throws Exception
	{
		Buffer len = new Buffer(4);
		BufferHelper.writeFixInt32(len, buf.readableBytes(), true);
		resp.getOutputStream().write(len.getRawBuffer(), len.getReadIndex(),
		        len.readableBytes());
		resp.getOutputStream().write(buf.getRawBuffer(), buf.getReadIndex(),
		        buf.readableBytes());
		resp.flushBuffer();
	}
	
	@Override
	protected void doPost(HttpServletRequest req, final HttpServletResponse resp)
	        throws ServletException, IOException
	{
		
		long begin = System.currentTimeMillis();
		Buffer buf = new Buffer(65536 + 100);
		resp.setBufferSize(65536 + 100);
		String userToken = req.getHeader("UserToken");
		String miscInfo = req.getHeader("C4MiscInfo");
		if (null == userToken)
		{
			userToken = "";
		}
		String[] misc = miscInfo.split("_");
		int index = Integer.parseInt(misc[0]);
		long timeout = Integer.parseInt(misc[1]);
		RemoteProxySessionManager.getInstance()
		        .resumeSessions(userToken, index);
		timeout = timeout * 1000;
		long deadline = begin + timeout;
		boolean sentData = false;
		try
		{
			int bodylen = req.getContentLength();
			if (bodylen > 0)
			{
				Buffer content = new Buffer(bodylen);
				int len = 0;
				while (len < bodylen)
				{
					content.read(req.getInputStream());
					len = content.readableBytes();
				}
				if (len > 0)
				{
					RemoteProxySessionManager.getInstance().dispatchEvent(
					        userToken, index, content);
				}
			}
			resp.setStatus(200);
			resp.setContentType("image/jpeg");
			resp.setHeader("C4LenHeader", "1");
			resp.setHeader("Connection", "keep-alive");
			//resp.setHeader("Transfer-Encoding", "chunked");
			do
			{
				long tmp = timeout;
				if(tmp > 10000)
				{
					tmp = 10000;
				}
				Event ev = RemoteProxySessionManager.getInstance()
				        .consumeReadyEvent(userToken, index, buf, tmp);
				if (buf.readable())
				{
					try
					{
						flushContent(resp, buf);
						// Thread.sleep(1);
					}
					catch (Throwable e)
					{
						logger.error(".", e);
						e.printStackTrace();
						resp.getOutputStream().close();
						RemoteProxySessionManager.getInstance().pauseSessions(
						        userToken, index);
						LinkedList<Event> eq = RemoteProxySessionManager
						        .getInstance().getEventQueue(userToken, index);
						synchronized (eq)
						{
							eq.addFirst(ev);
						}
						return;
					}
					
					sentData = true;
					buf.clear();
				}
				timeout = deadline - System.currentTimeMillis();
				if (timeout <= 0)
				{
					break;
				}
			} while (true);
			
			try
			{
				sentData = true;
				resp.getOutputStream().close();
			}
			catch (Exception e)
			{
				logger.error(".", e);
				e.printStackTrace();
				buf.clear();
				resp.getOutputStream().close();
			}
		}
		catch (Throwable e)
		{
			logger.error(".", e);
			resp.setStatus(400);
			e.printStackTrace();
			e.printStackTrace(new PrintStream(resp.getOutputStream()));
		}
		RemoteProxySessionManager.getInstance().pauseSessions(userToken, index);
		if (!sentData)
		{
			resp.setContentLength(0);
		}
		//resp.flushBuffer();
	}
}