/*
 * @author Gurpreet Pannu, Michael Norris, Priyam Patel
 */

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;

import Tools.Bencoder2;
import Tools.BencodingException;

/** Written very similar to TorrentInfo.java, some lines were taken from there */

public class Response {
	
	//Key used to retrieve the peer dictionary from the input byte array
	public final static ByteBuffer PEERS = ByteBuffer.wrap(new byte[]
		    {'p','e','e','r','s'});
	
	public final static ByteBuffer FAILURE_REASON = ByteBuffer.wrap(new byte[]
			{'f','a','i','l','u','r','e',' ','r','e','a','s','o','n'});
	
	public final static ByteBuffer TRACKER_ID = ByteBuffer.wrap(new byte[]
		    {'t','r','a','c','k','e','r',' ','i','d'});
	
	public final static ByteBuffer INTERVAL = ByteBuffer.wrap(new byte[]
			{'i','n','t','e','r','v','a','l'});
	
	public final static ByteBuffer COMPLETE = ByteBuffer.wrap(new byte[]
		    {'c','o','m','p','l','e','t','e'});
	
	public final static ByteBuffer INCOMPLETE = ByteBuffer.wrap(new byte[]
		    {'i','n','c','o','m','p','l','e','t','e'});
	
	//If the tracker doesn't like the connection for some reason, it will say why
	public String failure_reason;
	
	//The tracker's id, if given, it should be sent along with the periodic announcements
	public String tracker_id;
	
	//The max length of time that the client should wait before sending a periodic update announcement
	public int interval;
	
	//The number of peers with the entire file
	public int complete;
	
	//The number of Leechers
	public int incomplete;
	
	//The tracker's initial response, after sending it an announcement
	public byte[] response_file_bytes;
	
	//The decoded dictionary of the tracker's response
	private Map<ByteBuffer, Object> response_file_map;
	
	private ArrayList<Map<ByteBuffer, Object>> peer_map;
	
	@SuppressWarnings("unchecked")
	public Response(byte[] response_file_bytes) throws BencodingException {
		
		// Make sure the input is valid
		if(response_file_bytes == null || response_file_bytes.length == 0)
			throw new IllegalArgumentException("Torrent file bytes must be non-null and have at least 1 byte.");
		
		// Assign the byte array
		this.response_file_bytes = response_file_bytes;
		
		// Assign the metainfo map
		this.response_file_map = (Map<ByteBuffer,Object>)Bencoder2.decode(response_file_bytes);

		//Check if there was a failure and display the failure message
		ByteBuffer failBuffer = (ByteBuffer) this.response_file_map.get(Response.FAILURE_REASON);
		if (failBuffer != null) {
			try {
				this.failure_reason = new String(failBuffer.array(), "ASCII");
			} catch (UnsupportedEncodingException e1) {
				e1.printStackTrace();
			}
			if (this.failure_reason != null || this.failure_reason != "") {
				return;
			}
		}

		//Extract the peer dictionary
		ArrayList<Map<ByteBuffer, Object>> peer_map = (ArrayList<Map<ByteBuffer, Object>>) this.response_file_map.get(Response.PEERS);
		if(peer_map == null)
			throw new BencodingException("Could not extract peer dictionary from tracker response dictionary.  Corrupt file?");
		this.set_peer_map(peer_map);
		
		/*
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-1");
			digest.update(peer_bytes.array());
			byte[] peer_hash = digest.digest();
			this.peer_hash = ByteBuffer.wrap(peer_hash);
		} catch(NoSuchAlgorithmException nsae) {
			throw new BencodingException(nsae.getLocalizedMessage());
		}
		*/
		
		//Set the tracker id
		String str;
		if ((str = (String)this.response_file_map.get(Response.TRACKER_ID)) != null)
			this.tracker_id = new String(str);
		/*
		else
			System.out.println("The tracker id field was not sent with the tracker response.");
		*/
		
		//Take note of number of seeders and leechers, respectively
		int i;
		if ((i = (int) this.response_file_map.get(Response.COMPLETE)) != 0)
			this.complete = i;
		if ((i = (int) this.response_file_map.get(Response.INCOMPLETE)) != 0)
			this.incomplete = i;
		
		//Take note of the interval
		if ((i = (int) this.response_file_map.get(Response.INTERVAL)) != 0)
			this.interval = i;
	}
	
	/*
	 * Getters and Setters
	 */
	public ArrayList<Map<ByteBuffer, Object>> get_peer_map() {
		return peer_map;
	}
	public void set_peer_map(ArrayList<Map<ByteBuffer, Object>> peer_map2) {
		this.peer_map = peer_map2;
	}
}
