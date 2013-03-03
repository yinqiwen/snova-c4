/**
 * 
 */
package org.snova.c4.server.servlet;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.arch.buffer.Buffer;
import org.arch.encrypt.RC4;
import org.arch.misc.crypto.base64.Base64;
import org.snova.c4.server.session.v3.RemoteProxySessionManager;

/**
 * @author yinqiwen
 * 
 */
public class PushServlet extends HttpServlet
{
	
	public void init(ServletConfig servletConfig) throws ServletException
	{
		super.init(servletConfig);
		
		try
		{
			InputStream is = getServletContext().getResourceAsStream(
			        "/WEB-INF/rc4.key");
			byte[] buffer = new byte[8192];
			int n = is.read(buffer);
			RC4.setDefaultKey(new String(buffer, 0, n));
		}
		catch (Exception e)
		{
			throw new ServletException(e);
		}
	}
	
	@Override
	protected void doPost(HttpServletRequest req, final HttpServletResponse resp)
	        throws ServletException, IOException
	{
		String userToken = req.getHeader("UserToken");
		if (null == userToken)
		{
			userToken = "";
		}
		
		String rc4key = req.getHeader("RC4Key");
		if (null != rc4key)
		{
			byte[] tmp = Base64.decode(rc4key.getBytes());
			String key = new String(RC4.encrypt(tmp));
			if (!key.equals(RC4.getDefaultKey()))
			{
				resp.sendError(401);
				return;
			}
		}
		
		String miscInfo = req.getHeader("C4MiscInfo");
		String[] misc = miscInfo.split("_");
		int index = Integer.parseInt(misc[0]);
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
				try
				{
					RemoteProxySessionManager.getInstance().dispatchEvent(
					        userToken, index, content);
				}
				catch (Exception e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		resp.setHeader("Connection", "keep-alive");
		resp.setContentLength(0);
		resp.setStatus(200);
		resp.flushBuffer();
	}
}