/**
 * 
 */
package org.snova.c4.server.session.v3;

/**
 * @author yinqiwen
 *
 */
public class SessionAddressPair
{
	public SessionAddressPair(RemoteProxySession session, String address)
    {
	    this.session = session;
	    this.address = address;
    }
	public RemoteProxySession session;
	public String address;
}
