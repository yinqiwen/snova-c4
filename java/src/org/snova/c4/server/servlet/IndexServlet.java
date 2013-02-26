/**
 * 
 */
package org.snova.c4.server.servlet;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.snova.c4.server.Version;


/**
 * @author yinqiwen
 *
 */
public class IndexServlet extends HttpServlet 
{
	private static String content = null;
	
	private static String getContent()
	{
		if(null == content)
		{
			InputStream is = IndexServlet.class.getResourceAsStream("/html/index.html");
			if(null == is)
			{
				return "#####No resource found.";
			}
			byte[] buffer = new byte[64*1024];
			try
            {
	            int len = is.read(buffer);
	            content = new String(buffer,0 , len);
	            content = content.replace("${version}", Version.value);
            }
            catch (IOException e)
            {
	            // TODO Auto-generated catch block
	            e.printStackTrace();
            }
		}
		return content;
	}
	@Override
	protected void doGet(HttpServletRequest req, final HttpServletResponse resp)
	        throws ServletException, IOException
	{
		resp.getOutputStream().print(getContent());
	}
}
