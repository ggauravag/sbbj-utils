/**
 * 
 */
package in.dreambit.erputils.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

/**
 * @author Gaurav
 *
 */
public class Network {

	public static String getCurrentIP() throws IOException {
		URL whatismyip = new URL("http://checkip.amazonaws.com");
		BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));

		String ip = in.readLine(); // you get the IP as a String
		System.out.println("Current Public IP: " + ip);
		return ip;
	}

}
