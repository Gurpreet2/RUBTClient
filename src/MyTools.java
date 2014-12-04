import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

import Tools.BencodingException;
import Tools.TorrentInfo;

public class MyTools {

	/**
	 *  The logger for the class.
	 */
	private static final Logger logger = Logger.getLogger(MyTools.class.getName());
	
	/**
	 * This method converts a metainfo file into a TorrentInfo file.
	 * 
	 * @param torrentFile --> The meta info file that needs to be converted.
	 * @return --> The TorrentInfo file that is needed.
	 */
	public static TorrentInfo getTorrentInfo(String torrentFile) {
		FileInputStream fileInputStream;
        File metafile = new File(torrentFile);
        byte[] torrentByteFile = new byte[(int)metafile.length()];
        try {
        	fileInputStream = new FileInputStream(metafile);
        	fileInputStream.read(torrentByteFile);
        	fileInputStream.close();
        } catch (Exception e) {
        	System.out.println("There was a problem converting the metafile into a byte array.");
        	e.printStackTrace();
        }
        TorrentInfo torrentInfo = null;
		try {
			torrentInfo = new TorrentInfo(torrentByteFile);
		} catch (BencodingException e) {
			System.out.println("There was a problem converting the byte array of the metafile into a TorrentInfo file.");
			e.printStackTrace();
		}
		return torrentInfo;
	}
	
	
	/**
	 * These next method converts a byte array or a string into a hex string with percents!
	 * 
	 * @param biteArray -> This byte array will be converted into hex format
	 * @param bool -> This indicates whether or not the returned string should include percent signs. true means should have percents
	 * @return
	 */
	public static String toHex(Object o, boolean bool) {
		byte[] biteArray = null;
		if (o instanceof String) {
			String str = (String) o;
			biteArray = str.getBytes();
		} else if (o instanceof byte[]) {
			biteArray = (byte[]) o;
		}
		if (biteArray == null) return null;
		if (biteArray.length == 0) return "";
		StringBuilder stringBuilder = new StringBuilder();
		if (bool)
			for (byte b : biteArray) {
				stringBuilder.append("%" + String.format("%02x", b));
			}
		else
			for (byte b : biteArray) {
				stringBuilder.append(String.format("%02x", b));
			}
		return stringBuilder.toString();
	}
	
	/**
	 * This method will put the list of peers that came in the tracker response into an arraylist of <Peer> objects.
	 * 
	 * @param peer_map - The peer map that contains the peer information
	 * @param client
	 * @return
	 */
	public static ArrayList<Peer> createPeerList(RUBTClient client, ArrayList<Map<ByteBuffer, Object>> peer_map) {
		ArrayList<Peer> peers = new ArrayList<Peer>();
        Peer p = null;
        ByteBuffer byteBuffer;
        String peer_id, peer_ip;
        int peer_port;
        for (int i = 0; i < peer_map.size(); i++) {
        	byteBuffer = (ByteBuffer)peer_map.get(i).get(ByteBuffer.wrap(new byte[] {'p', 'e', 'e', 'r', ' ', 'i', 'd'}));
            peer_id = new String(byteBuffer.array());
           
            byteBuffer = (ByteBuffer)peer_map.get(i).get(ByteBuffer.wrap(new byte[] {'i', 'p'}));
            peer_ip = new String(byteBuffer.array());
       
            peer_port =  (Integer) peer_map.get(i).get(ByteBuffer.wrap(new byte[] { 'p', 'o', 'r', 't' })); 
            p = new Peer(peer_id, peer_ip, peer_port, client);
            peers.add(p);
        }
		return peers;
	}
	
	
	/**
	 * This method is used to find an open port to connect to a peer. Specifically, to the peer ip 
	 * address (if there is one) that is specified in the command line argument.
	 * 
	 * @param ip -The connection needs to be started with this peer ip.
	 * @return - A port that is open. Return 0 if an open port cannot be found.
	 */
	public static int findPort(String ip) {
		for (int i = 6881; i < 6890; i++) {
			try (Socket socket = new Socket(ip, i)) {
				if (socket != null) {
					return i;
				}
			} catch (UnknownHostException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		return 0;
	}
	
	
	/**
	 *  If the client was shut down and restarted, this method will refill the rawFileBytes with the bytes from the download file
	 *  if it exists. To ensure that the rawFileBytes are correctly brought in from the file, piece hashes are created from their
	 *  pieces and checked against the hashes of 
	 */
	public static boolean setDownloadedBytes(RUBTClient client) {
        File file = new File(RUBTClient.downloadFile);
        if (!file.exists()) return true;
        client.bytesLeft = client.torrentInfo.file_length;
        client.havePieces = new boolean[client.numOfPieces];
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		byte[] piece = null, getHash = null;
		int piece_length = client.torrentInfo.piece_length;
		client.rawFileBytes = new byte[client.torrentInfo.file_length];
		client.rawFileBytes = MyTools.getFileBytes(file);
        for (int i = 0; i < client.numOfPieces; i++) {
        	piece = Arrays.copyOfRange(client.rawFileBytes, i*piece_length, (i+1)*piece_length);
        	getHash = md.digest(piece);
        	boolean isGood = true;
        	byte[] pieceHash = client.torrentInfo.piece_hashes[i].array();
        	for (int j = 0; j < pieceHash.length; j++)
        		if (pieceHash[j] != getHash[j]) isGood = false;
        	if (isGood) {
        		client.havePieces[i] = true;
        		client.bytesLeft = client.bytesLeft - piece_length;
        		client.bytesDownloaded = client.bytesDownloaded + piece_length;
        		client.numOfHavePieces++;
        	}
        	md.reset();
        }
        return false;
	}
	
	
	/**
	 * This method saves the bytes that have been downloaded so far if something goes wrong or the user stops the client.
	 * @param client
	 */
	public synchronized static void saveDownloadedPieces(RUBTClient client) {
		File file = new File(RUBTClient.downloadFile);
		try (FileOutputStream fos = new FileOutputStream(RUBTClient.downloadFile)){
			if (!file.exists()) file.createNewFile();
			fos.write(client.rawFileBytes);
		} catch (IOException e) {
			System.err.println("There was an error trying to put the downloaded bytes into the download file.");
			e.printStackTrace();
		}
		logger.info("Saved rawFileBytes to file.");
	}
	
	
	/**
	 * This method converts a file into a byte array, useful when restarting a download after stopping.
	 * @param file - the file that will be converted
	 * @return
	 * @throws IOException
	 */
	public static byte[] getFileBytes(File file) {
		if (file == null) return null; //sanity check
		Path path = file.toPath();
		byte[] biteArray = new byte[(int) file.getTotalSpace()];
		try {
			biteArray = java.nio.file.Files.readAllBytes(path);
		} catch (IOException e) {
			System.err.println("There was an error converting the file into a byte array.");
			e.printStackTrace();
		}
		return biteArray;
	}
	
	
	/**This method sets a bit in a byte array. Possibly the most important method in here... wait a sec...
	 * 
	 * @param biteArray - the array that needs changing
	 * @param i - the location of the bit that needs to be changed
	 * @return
	 */
	public static byte[] setBit(byte[] biteArray, int i) {
		biteArray[i/8] |= (1 << 7-i%8);
		return biteArray;
	}
	
	
	/**This method resets a bit. Ok this one is more important... maybe...
	 * 
	 * @param biteArray - byte array that needs changing
	 * @param i - location of the bit to be changed
	 * @return
	 */
	public static byte[] resetBit(byte[] biteArray, int i) {
		biteArray[i/8] &= (1 << 7-i%8);
		return biteArray;
	}
	
	
	/**This method tells if a bit in a byte (a byte we want to spy on) is set.
	 * 
	 * @param biteArray - NSA-targeted byte array
	 * @param i - location of bit to check
	 * @return
	 */
	public static boolean isBitSet(byte[] biteArray, int i) {
		int b = (biteArray[i/8] >> 7-i%8) & 1;
		if (b == 0) return false;
		return true;
	}
}
