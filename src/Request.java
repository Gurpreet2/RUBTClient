/*
 * @author Gurpreet Pannu, Priyam Patel, Michael Norris
 */

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import Tools.BencodingException;

public class Request {

	static final int port = 6881;
	
	boolean existPeerId = false;
	
	String infoHash;
	
	String clientId;
	
	public Request(RUBTClient client, String event) {
		
		//Define the pieces of the URL that will be in the announcement, then assemble the URL
		String announceURL = client.torrentInfo.announce_url.toString();
		this.infoHash = MyTools.toHex((client.torrentInfo.info_hash).array(), true);
		this.clientId = MyTools.toHex(client.clientId, true);
		URL url = null;
		try {
			url = new URL(announceURL + "?info_hash=" + this.infoHash + "&peer_id=" + clientId + 
					"&port=" + port + "&uploaded=" + client.bytesUploaded + "&downloaded=" + client.bytesDownloaded +
					"&left=" + client.bytesLeft + "&event=" + event);
		} catch (MalformedURLException e1) {
			System.out.println("The url was not formed correctly.");
			System.out.println("This was the malformed url: " + url);
			e1.printStackTrace();
		}
		
		//Setup the connection and connect, then receive the response and save it into the client
		HttpURLConnection urlConnection = null;
		DataInputStream inputStream;
		byte[] responseBytes = null;
		try (Socket socket = new Socket(announceURL.substring(7, 20), Integer.parseInt(announceURL.substring(21, 25)))) {
			urlConnection = (HttpURLConnection) url.openConnection();
			if (urlConnection.getResponseCode() >= 400) {
				inputStream = new DataInputStream(urlConnection.getErrorStream());
				System.out.println(inputStream.readUTF());
				return;
			} else {
				inputStream = new DataInputStream(urlConnection.getInputStream());
			}
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			byte[] responseByte = new byte[1];
			while((inputStream.read(responseByte)) != -1) {
				byteArrayOutputStream.write(responseByte);
			}
			responseBytes = byteArrayOutputStream.toByteArray();
			
			/* In this next try clause:
			 * Decode the tracker response message and store it in client
			 * 
			 * There are two different areas of storage, because need to keep one for the original response 
			 * and also need to keep one for the periodic tracker announcements
			 */
			try {
				if (event.equals("started"))
					client.response1 = new Response(responseBytes);
				else
					client.response2 = new Response(responseBytes);
			} catch (BencodingException e) {
				e.printStackTrace();
			}
			byteArrayOutputStream.close();
			inputStream.close();
			urlConnection.disconnect();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
