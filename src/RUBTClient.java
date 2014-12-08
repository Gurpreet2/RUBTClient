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
	String metainfoFileName;
	
	/**
	 *  The name of the torrent download file.
	 */
	String downloadFileName;
	
	/**
	 *  A peer ip. If specified, client will connect to it and start downloading from it.
	 */
	String onlyPeer = null;
	
	/**
	 *  This will contain the raw bytes being downloaded
	 */
	volatile RandomAccessFile theDownloadFile;
	
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
	volatile ArrayList<Peer> peers;
	
	/**
	 *  List of Peers that the client is currently interacting with;
	 */
	volatile ArrayList<Peer> neighboring_peers = new ArrayList<Peer>(10);
	
	/**
	 *  List of peers that the client shouldn't connect to because they're terrible.
	 */
	volatile ArrayList<String> bad_peers = new ArrayList<String>(10);
	
	/**
	 *  The number of active peers. This value is changed after handshakes are sent and received. Also after peers are disconnected
	 *  via the shutdown method.
	 */
	volatile int numOfActivePeers = 0;
	
	/**
	 *  A boolean array indicating whether or not a piece has been downloaded. Will help for sending bitfield.
	 */
	volatile boolean[] havePieces;
	
	/**
	 *  Number of blocks there are to download
	 */
	volatile int numOfPieces;
	
	/**
	 *  The number of pieces the client has.
	 */
	volatile int numOfHavePieces = 0;
	
	/**
	 *  Interval for sending announcements to tracker.
	 */
	static int announceTimerInterval = 180;
	
	/**
	 *  Amount of times a client should try to connect to a peer before giving up on the peer.
	 */
	final int MAX_CONNECTION_ATTEMPTS = 3;
	
	/**
	 *  Maximum number of connections allowed.
	 */
	final int MAX_CONNECTIONS = 10;
	
	/**
	 *  Number of bytes that can be uploaded in interval MAX_THROTTLE_INTERVAL by each peer.
	 */
	volatile int UPLOAD_LIMIT;
	
	/**
	 *  Maximum number of bytes that can be uploaded in interval MAX_THROTTLE_INTERVAL.
	 */
	final int MAX_UPLOAD_LIMIT = 130000;
	
	/**
	 *  Upload limit cannot go lower than this.
	 */
	volatile int UPLOAD_LOWER_LIMIT;
	
	/**
	 *  Number of bytes that can be downloaded in interval MAX_THROTTLE_INTERVAL by each peer.
	 */
	volatile int DOWNLOAD_LIMIT;
	
	/**
	 *  Maximum number of bytes that can be downloaded in interval MAX_THROTTLE_INTERVAL.
	 */
	final int MAX_DOWNLOAD_LIMIT = 390000;
	
	/**
	 *  Download limit cannot go lower than this.
	 */
	int DOWNLOAD_LOWER_LIMIT = 35000;
	
	/**
	 *  If the client is seeding.
	 */
	volatile boolean amSeeding = false;
	
	/**
	 *  How far along the download is.
	 */
	int percentComplete;

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
		client.metainfoFileName = args[0];
		client.downloadFileName = args[1];
		if (args.length == 3) client.onlyPeer = args[2];
		
		// Convert the torrent metainfo file into a TorrentInfo file, and use it to initialize some variables for client
        client.torrentInfo = MyTools.getTorrentInfo(client.metainfoFileName);
        client.numOfPieces = client.torrentInfo.piece_hashes.length;
	    File file = new File(client.downloadFileName);
        // If the client was stopped earlier, and the download file exists, fill rawFileBytes with the bytes from the file
        if (file.exists()) {
        	logger.info("Download file found, resuming download.");
        	try {
				client.theDownloadFile = new RandomAccessFile(file, "rwd");
				MyTools.setDownloadedBytes(client);
			} catch (IOException e) {
				e.printStackTrace();
			}
        } else {
        	// Else, initialize variables needed for a fresh download
        	logger.info("Starting new download.");
	        client.bytesLeft = client.torrentInfo.file_length;
	        client.havePieces = new boolean[client.numOfPieces];
	        try {
	        	file.createNewFile();
				client.theDownloadFile = new RandomAccessFile(file, "rwd");
				client.theDownloadFile.setLength(client.torrentInfo.file_length);
			} catch (IOException e) {
				e.printStackTrace();
			}
        }
        client.percentComplete = client.numOfHavePieces*100/client.numOfPieces;
        if (client.numOfHavePieces == client.numOfPieces) client.amSeeding = true;
        
        // Connect to the Tracker and send a started announcement
        new Request(client, "started");
        logger.info("Sent started announcement to tracker.");
        
        // Check if something went wrong with the tracker response and print the failure reason.
        if (client.response1.failure_reason != null) {
        	System.out.println("Torrent failed to download, failure reason:\n   " + client.response1.failure_reason);
        	logger.info(client.response1.failure_reason);
        	return;
        }
        
        // Input Reader
        InputReader ir = client.new InputReader();
        ir.start();
        
        // Put peer_map into a peer array so it can be more easily used and read, also start the threads for each peer
        //if (args.length == 2) {
	        ArrayList<Map<ByteBuffer, Object>> peer_map = client.response1.get_peer_map();
	        client.peers = MyTools.createPeerList(client, peer_map);
	        // Start connecting to and messaging peers
	        for (Peer peer : client.peers) {
				if (client.numOfActivePeers < client.MAX_CONNECTIONS) {
					//if(peer.peerIp.startsWith("128.6.171.")){ //TODO: Allow all peers

					if(client.onlyPeer != null && peer.peerIp.equals(client.onlyPeer) && !peer.peerId.equals(client.clientId)){
						System.out.println("PEER ID: " + peer.peerId + " CLIENT ID: " + client.clientId);
		        		System.out.println("Starting thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
		    			peer.start();
					}
					else if(client.onlyPeer == null){
		        		System.out.println("Starting thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
		    			peer.start();
					//}
					}
				}
				else{
	    			logger.info("Unable to connect with Peer ID : [ " + peer.peerId + " ]. Maximum number of connections reached.");
				}
	        }
       /* } else {
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
        	System.out.println("Added peer");
        	client.peers.add(new Peer(null, client.onlyPeer, port, client));
<<<<<<< Updated upstream
        }
=======
        }*/
        //client.peers.get(0).start();
        
        // Thread start for client
        new Thread(client).start();
        startGui(client);
        
		//if (client.onlyPeer == null) {
			PeerListener peerListener = client.new PeerListener();
			peerListener.runPeerListener();
		//}
	}


	/**
	 *  This is the thread for the client. It sends periodic tracker announcements, waits for input
	 *  from the user, sends have messages, and requests, and runs until the file is downloaded.
	 */
	public void run() {
		
        logger.info("Started thread for client. Our peer ID: " + this.clientId);
		// Setup a timer to send periodic announcements to the tracker.
		Timer timer = new Timer();
		TimerTask timerTask = new TimerTask() {
			@Override
			public void run() {
				
				/* I left "RUBTClient.this" in front of the relevant variables for clarity. */
				// Send the regular announcement
				new Request(RUBTClient.this, "");
				logger.info("Regular announcement sent to tracker.");
				// Update the tracker announce interval if need be
				if (RUBTClient.this.response2 != null) //sanity check
					RUBTClient.announceTimerInterval = 
						(RUBTClient.this.response2.interval > 180) ? 180 : RUBTClient.this.response2.interval;
				
				if (RUBTClient.this.onlyPeer == null) {
					// Parse the tracker response and add in new peers
					ArrayList<Map<ByteBuffer, Object>> peer_map = RUBTClient.this.response2.get_peer_map();
			        ArrayList<Peer> peers = MyTools.createPeerList(RUBTClient.this, peer_map);
			        for (Peer peer : peers) {
			        	if (RUBTClient.this.numOfActivePeers < RUBTClient.this.MAX_CONNECTIONS 
			        			&& !RUBTClient.this.neighboring_peers.contains(peer) 
			        			&& !RUBTClient.this.bad_peers.contains(peer.peerIp)) {
			        		System.out.println("Added peer");
			        		
			        		RUBTClient.this.peers.add(peer);
			        		System.out.println("Starting thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
			    			peer.start();
			        	}
			        }
		        
			        // Look at the old peers and see if any of them deserve a chance to be connected to, again
			        for (Peer peer : RUBTClient.this.peers) {
			        	if (peer.connectionAttempts < RUBTClient.this.MAX_CONNECTION_ATTEMPTS 
			        			&& (peer.peerIp != null ? !RUBTClient.this.neighboring_peers.contains(peer) : true) // Adjusted for incoming peer connections
			        			// In case I add something later on that puts peers in bad_peers even when connectionAttempts < MAX_CONNECTION_ATTEMPTS
			        			&& !RUBTClient.this.bad_peers.contains(peer.peerIp)) {
			        		System.out.println("Starting NEW thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
			    			peer.start();
			        	}
			        }
				}
			}
		};
		timer.schedule(timerTask, RUBTClient.announceTimerInterval*1000, RUBTClient.announceTimerInterval*1000);
		
		int numOfActivePeers = this.numOfActivePeers;
		int percentComplete;
		if (this.numOfHavePieces != this.numOfPieces)
			percentComplete = this.numOfHavePieces*100/this.numOfPieces;
		else
			percentComplete = 100;
		System.out.println("Download is " + percentComplete + "% complete.");
		while(this.RUN) {
			
			// Set the amount completed
			this.percentComplete = this.numOfHavePieces*100/this.numOfPieces;
			
			// Set the upload and download limits for each peer
			if (numOfActivePeers != this.numOfActivePeers) {
				if (this.numOfActivePeers < 2) {
					this.UPLOAD_LIMIT = this.MAX_UPLOAD_LIMIT;
					this.DOWNLOAD_LIMIT = this.MAX_DOWNLOAD_LIMIT;
				} else {
					this.UPLOAD_LIMIT = 
							(this.MAX_UPLOAD_LIMIT / this.numOfActivePeers < this.torrentInfo.piece_length) ? this.UPLOAD_LOWER_LIMIT : (this.MAX_UPLOAD_LIMIT / this.numOfActivePeers);
					this.DOWNLOAD_LIMIT = 
							(this.MAX_DOWNLOAD_LIMIT / this.numOfActivePeers < this.torrentInfo.piece_length) ? this.DOWNLOAD_LOWER_LIMIT : (this.MAX_DOWNLOAD_LIMIT / this.numOfActivePeers);
				}
				numOfActivePeers = this.numOfActivePeers;
			}
				
			// Prints how far along the download is, whenever the download progresses.
			if (this.numOfHavePieces != this.numOfPieces) {
				if (percentComplete != this.percentComplete) {
					System.out.println("Download is " + this.percentComplete + "% complete.");
					percentComplete = this.percentComplete;
					if (percentComplete == 100) {
						new Request(this, "completed");
						logger.info("Sent completed announcement to tracker.");
						logger.info("Seeding...");
					}
				}
			}
		}
		
		timer.cancel();
	}
	
	
	/**
	 * Creates a Gui object and opens up the interface.
	 */
	private static void startGui(RUBTClient rc){
		Gui gui;
		gui = new Gui(rc);
		gui.setSize(450,450);
		gui.setResizable(false);
		gui.setVisible(true);
		gui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
	}
	
	
	/** Changes made by: Priyam Patel 
	 *  Added method is called from GUI to update progress bar 
	 * @returns int: percent value left to download 
	 */
	public int getProgressBarPercent() {
		//double fraction = ((double)this.bytesDownloaded/(double)this.torrentInfo.file_length);
		return this.percentComplete;//(int)fraction*100;
	}
	
	
	/**
	 * This class listens for incoming connections from peers and allocates a socket for a connection.
	 *
	 */
	class PeerListener extends Thread {
		
		String onlyPeer = null;
		
		public PeerListener() {
		}
		
		public PeerListener(String onlyPeer){
			this.onlyPeer = onlyPeer;
		}
		
		public void runPeerListener() {
			
			try (ServerSocket serverSocket = new ServerSocket(Request.port)){
				logger.info("PeerListener is waiting for connections.");
				
				while (true) {
					Socket peerSocket = serverSocket.accept();
					System.out.println("ACCEPTED A NEW PEER!!!!!!!!");
					Peer peer = new Peer(null, peerSocket.getInetAddress().getHostAddress(), Request.port, peerSocket, RUBTClient.this);
					RUBTClient.this.peers.add(peer);
					if (RUBTClient.this.numOfActivePeers < RUBTClient.this.MAX_CONNECTIONS){
						new Thread(peer).start();
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
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
			
			for (Peer peer : RUBTClient.this.neighboring_peers) {
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
		
		
		// TODO get rid of this and doing it with the gui
		public void pause() {
			new Request(RUBTClient.this, "stopped");
		}
	}
}
