/**
 * 
 */
package org.snova.httpdns;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author wqy
 * 
 */
public class DNSServlet extends HttpServlet
{
	private static String	pattern;
	
	private static String getContent()
	{
		if (null == pattern)
		{
			InputStream is = DNSServlet.class
			        .getResourceAsStream("/template/result.html.template");
			byte[] buffer = new byte[64 * 1024];
			try
			{
				int len = is.read(buffer);
				pattern = new String(buffer, 0, len);
			}
			catch (IOException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return pattern;
	}
	
	private String[] getIP(String host) throws UnknownHostException
	{
		InetAddress[] addrs = InetAddress.getAllByName(host);
		String[] ret = new String[addrs.length];
		for (int i = 0; i < addrs.length; i++)
		{
			ret[i] = addrs[i].getHostAddress();
		}
		return ret;
	}
	
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
	        throws ServletException, IOException
	{
		String pattern = getContent();
		String host = req.getParameter("Domain");
		if (null != host && host.length() > 0)
		{
			String result = pattern.replace("${DOMAIN}", host);
			String[] ips = getIP(host);
			StringBuilder buffer = new StringBuilder();
			for (int i = 0; i < ips.length; i++)
			{
				buffer.append("IP").append(i).append(": ").append(ips[i])
				        .append("<br />");
			}
			result = result.replace("${CONTENT}", buffer.toString());
			resp.setContentLength(result.length());
			resp.getOutputStream().print(result);
			return;
		}
		resp.setStatus(400);
		resp.getOutputStream().print("No Host para.");
	}
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
	        throws ServletException, IOException
	{
		String host = req.getParameter("Domain");
		if (null != host && host.length() > 0)
		{
			String[] ips = getIP(host);
			StringBuilder buffer = new StringBuilder();
			buffer.append("[");
			for (int i = 0; i < ips.length; i++)
			{
				buffer.append("\"");
				buffer.append(ips[i]);
				buffer.append("\"");
				if (i != ips.length - 1)
				{
					buffer.append(",");
				}
			}
			buffer.append("]");
			resp.setContentLength(buffer.length());
			resp.getOutputStream().print(buffer.toString());
			return;
		}
		resp.setStatus(400);
		resp.getOutputStream().print("No Host para.");
	}
}
