/*
 *  @author Gurpreet Pannu, Priyam Patel, Michael Norris
 */



import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;
import javax.swing.JFrame;

import Tools.TorrentInfo;

public class RUBTClient extends JFrame implements Runnable{
	
	/**
	 *  Needed this.
	 */
	private static final long serialVersionUID = 1L;
	
	/**
	 *  The logger for the class.
	 */
	private static final Logger logger = Logger.getLogger(RUBTClient.class.getName());
	
	/**
	 *  The name of the torrent metainfo file.
	 */
	static String metainfoFile;
	
	/**
	 *  The name of the torrent download file.
	 */
	static String downloadFile;
	
	/**
	 *  A peer ip. If specified, client will connect to it and start downloading from it.
	 */
	String onlyPeer;
	
	/**
	 *  This will be the peer id associated with the client
	 */
	String clientId;
	
	/**
	 *  This will be the amount of bytes left to download from peers, initially will be set to size of full file
	 */
	volatile int bytesLeft;
	
	/**
	 *  How many bytes have been uploaded to peers. (Initialization only for fresh downloads, corrected in main if continuing download)
	 */
	volatile int bytesUploaded = 0;

	/**
	 *  How many bytes have been downloaded so far. (Initialization only for fresh downloads, corrected in main if continuing download)
	 */
	volatile int bytesDownloaded = 0;
	
	/**
	 *  The metainfo file of the torrent will be stored in here
	 */
	TorrentInfo torrentInfo;
	
	/**
	 *  Response from the tracker after sending it a "started" announcement will be stored in response_1
	 */
	Response response1;

	/**
	 *  Responses from the tracker after sending it periodic announcements will be stored in response_2
	 */
	Response response2;
	
	/**
	 *  This arraylist stores the peers in a peer objects arraylist
	 */
	ArrayList<Peer> peers;
	
	/**
	 *  Number of blocks there are to download
	 */
	volatile int numOfPieces;
	
	/**
	 *  This will contain the raw bytes being downloaded
	 */
	volatile byte[] rawFileBytes;
	
	/**
	 *  A boolean array indicating whether or not a piece has been downloaded. Will help for sending bitfield.
	 */
	volatile boolean[] havePieces;
	
	/**
	 *  The number of pieces the client has.
	 */
	volatile int numOfHavePieces = 0;
	
	/**
	 *  An array that will be used to send have messages to peers 
	 *  (set to true when piece finishes downloading, reset to false after sending have message to all peers)
	 */
	volatile LinkedBlockingQueue<Message> haveSendQueue = new LinkedBlockingQueue<Message>(20);
	
	/**
	 *  This will be used to send requests to peers, and to limit the amount of requests to send.
	 */
	volatile LinkedBlockingQueue<Message> requestSendQueue = new LinkedBlockingQueue<Message>(20);
	
	/**
	 *  Interval for sending announcements to tracker
	 */
	static int announceTimerInterval = 120;
	
	/**
	 *  The number of active peers.
	 */
	volatile int numActivePeers = 0;

	/**
	 *  Value that is true while file is downloading, false when file finishes downloading.
	 */
	volatile boolean RUN = true;
	
	public RUBTClient() {
		
		// Generate a peer ID for the client
		String str = UUID.randomUUID().toString();
		this.clientId = "-RUBT11-" + str.substring(0,8) + str.substring(9,13);// + str.substring(14,16);// + str.substring(19,23);
	}
	
	public static void main(String[] args) {
		
		RUBTClient client = new RUBTClient();
		
		// Make sure that the number of command line arguments is 2 or 3
		if (args.length != 2 && args.length != 3) {
            System.out.println("Usage: java RUBTClient <torrent file> <download file>\n");
            System.out.println("or\n");
            System.out.println("Usage: java RUBTClient <torrent file> <download file> <peer ip>\n");
            return;
        }
		
		// Create a text file for the logger
		/*try {
			logger.setUseParentHandlers(false); // Don't print logs to the console. (Less clutter)
			FileHandler filehandler = new FileHandler("logger.txt");
			logger.addHandler(filehandler);
			SimpleFormatter simpleformatter = new SimpleFormatter();
			filehandler.setFormatter(simpleformatter);
		} catch (IOException e) {
			System.err.println("The logger text save file could not be set up.");
			e.printStackTrace();
		}*/
		
		// Hold the cammand line arguments in some variables so they can be called from methods outside main
		RUBTClient.metainfoFile = args[0];
		RUBTClient.downloadFile = args[1];
		if (args.length == 3) client.onlyPeer = args[2];
		
		// Convert the torrent metainfo file into a TorrentInfo file, and use it to initialize some variables for client
        client.torrentInfo = MyTools.getTorrentInfo(RUBTClient.metainfoFile);
        client.numOfPieces = client.torrentInfo.piece_hashes.length;
        // If the client was stopped earlier, and the download file exists, fill rawFileBytes with the bytes from the file
        if (!MyTools.setDownloadedBytes(client)) {
        	logger.info("Download file found, resuming download.");
        } else {
        	// Else, initialize variables needed for a fresh download
        	logger.info("Starting new download.");
	        client.bytesLeft = client.torrentInfo.file_length;
	        client.havePieces = new boolean[client.numOfPieces];
	        client.rawFileBytes = new byte[client.torrentInfo.file_length];
        }
        
        // Connect to the Tracker and send a started announcement
        new Request(client, "started");
        logger.info("Sent started announcement to tracker.");
        
        // Check if something went wrong with the tracker response and print the failure reason.
        if (client.response1.failure_reason != null) {
        	System.out.println("Torrent failed to download, failure reason:\n   " + client.response1.failure_reason);
        	logger.info(client.response1.failure_reason);
        	return;
        }
        
        /*// Input Reader
        InputReader ir = client.new InputReader();
        ir.start();*/
        
        // Put peer_map into a peer array so it can be more easily used and read, also start the threads for each peer
        if (args.length == 2) {
	        ArrayList<Map<ByteBuffer, Object>> peer_map = client.response1.get_peer_map();
	        client.peers = MyTools.createPeerList(client, peer_map);
	        // Start connecting to and messaging peers
	        /*for (Peer peer : client.peers) {
	        	System.out.println("Starting thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
	    		peer.start();
	        }*/
        } else {
        	int port, count = 0;
        	do {
        		port = MyTools.findPort(client.onlyPeer);
        		count++;
        	} while (port == 0 && count < 3);
        	if (count == 3) {
        		logger.info("No open port found for single Peer: (" + client.onlyPeer + "). Exiting program...");
        		System.err.println("Could not find an open port to connect to the peer in the range 6881 - 6890. Exiting...");
        		return;
        	}
        	client.peers.add(new Peer(null, client.onlyPeer, port, client));
        }
        client.peers.get(1).start();
        
        // Thread start for client
        new Thread(client).start();
        
		//PeerListener peerListener = client.new PeerListener();
		//peerListener.runPeerListener(); TODO does this even work
	}


	/**
	 *  This is the thread for the client. It sends periodic tracker announcements, waits for input
	 *  from the user, sends have messages, and requests, and runs until the file is downloaded.
	 */
	public void run() {
		
        logger.info("Started thread for client.");
		
		// Setup a timer to send periodic announcements to the tracker.
		Timer timer = new Timer();
		TimerTask timerTask = new TimerTask() {
			@Override
			public void run() {
				logger.info("Regular announcement sent to tracker.");
				new Request(RUBTClient.this, "");
				if (RUBTClient.this.response2 != null) //sanity check
					RUBTClient.announceTimerInterval = 
						(RUBTClient.this.response2.interval > 120) ? 120 : RUBTClient.this.response2.interval;
			}
		};
		timer.schedule(timerTask, 60*1000, RUBTClient.announceTimerInterval * 1000);
		
		int downloaded = this.bytesDownloaded;
		System.out.println("Download is " + (this.bytesDownloaded*100/this.rawFileBytes.length) + "% complete.");
		while(this.RUN) {
			
			// Prints how far along the download is, whenever the download progresses.
			if (downloaded != this.bytesDownloaded) {
				System.out.println("Download is " + (this.bytesDownloaded*100/this.rawFileBytes.length) + "% complete.");
				downloaded = this.bytesDownloaded;
			}
			
			// This sends have messages when required
			if (!this.haveSendQueue.isEmpty()) {
				try {
					Message message = this.haveSendQueue.take();
					for (Peer peer : peers) {
						if (peer.socket == null || peer.socketOutput == null) continue;
						if (peer.socket.isClosed()) continue;
						peer.sendMessage(message);
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			if (this.bytesLeft == 0) this.RUN = false;
		}
		
		timer.cancel();
		
		new Request(this, "completed");
		logger.info("Sent completed announcement to tracker.");
        
        // After the threads have been shutdown, save the downloaded rawfilebytes into a file.
        MyTools.saveDownloadedPieces(this);
	}
	
	
	/**
	 * This class listens for incoming connections from peers and allocates a socket for a connection.
	 *
	 */
	class PeerListener extends Thread {
		
		public PeerListener() {
		}
		
		public void runPeerListener() {
			
			ServerSocket serverSocket;
			Socket peerSocket;
			
			try {
				serverSocket = new ServerSocket(Request.port);
				logger.info("PeerListener is waiting for connections.");
				
				while (true) {
					peerSocket = serverSocket.accept();
					newPeer(peerSocket);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		public void newPeer(Socket socket) {
			
			Peer peer = new Peer(null, socket.getInetAddress().getHostAddress(), socket.getPort(), RUBTClient.this);
			new Thread(peer).start();
		}
	}
	
	
	/**
	 * This class reads input from the user
	 * TODO fix this
	 * 
	 */
	class InputReader extends Thread{
		
		public InputReader () {
			
		}
		
		public void run() {
			BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in));
			String str;
			
			try {
				str = inputReader.readLine().toLowerCase();
				while (true) {
					if (str.equals("q") || str.equals("quit")) break;
					if (str.equals("stop")) pause();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			for (Peer peer : RUBTClient.this.peers) {
				if (peer.socket != null) {
					peer.shutdown();
					RUBTClient.this.RUN = false;
					try {
						Thread.sleep(300);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					System.exit(1);
				}
			}
		}
		
		
		
		public void pause() {
			new Request(RUBTClient.this, "stopped");
		}
	}
}
