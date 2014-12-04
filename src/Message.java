

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class Message {

	/**
	 *  The logger for the class.
	 */
	private static final Logger logger = Logger.getLogger(Message.class.getName());
	
	int length_prefix;
	byte message_id = (byte) -1;
	int[] intPayload = null;
	byte[] bytePayload = null;
	
	
	/**
	 * Constructor for keep-alive, choke, unchoke, interested, and uninterested messages.
	 * @param type
	 */
	public Message (byte type) {
		this.length_prefix = 1;
		this.message_id = type;
	}
	
	
	/**
	 * Constructor for bitfield messages.
	 * 
	 * @param length_prefix
	 * @param message_id
	 * @param payload
	 */
	public Message (int length_prefix, byte message_id, byte[] payload) {
		this.length_prefix = length_prefix;
		this.message_id = message_id;
		this.bytePayload = payload;
	}
	
	
	/**
	 * Constructor for have messages.
	 * 
	 * @param length_prefix
	 * @param message_id
	 * @param payload
	 */
	public Message (int length_prefix, byte message_id, int payload) {
		this.length_prefix = length_prefix;
		this.message_id = message_id;
		int[] intPayload = {payload};
		this.intPayload = intPayload;
	}
	
	
	/**
	 * Constructor for request and cancel messages.
	 * @param length_prefix - the length of the message
	 * @param message_id - the type of the message
	 * @param payload
	 */
	public Message (int length_prefix, byte message_id, int[] payload) {
		this.length_prefix = length_prefix;
		this.message_id = message_id;
		this.intPayload = payload;
	}
	
	
	/**
	 * Constructor for piece messages.
	 * @param index - 0-based index of the piece.
	 * @param begin - Block index within the piece.
	 * @param block - Block to send.
	 */
	public Message (int index, int begin, byte[] block) {
		this.length_prefix = 9 + block.length;
		this.message_id = (byte) 7;
		int[] intPayload = {index, begin};
		this.intPayload = intPayload;
		this.bytePayload = block;
	}
	
	
	/**
	 * This method finds out what message the peer is sending to the client, and deals with it.
	 * 
	 * @param peer
	 * @param length - length of the incoming message
	 * @throws IOException 
	 */
	public static void dealWithMessage(Peer peer, DataInputStream dis) throws IOException {
		logger.info("Reading message from peer : " + peer.peerId);
		int length = dis.readInt();
		if (length < 1 || length > 131081) return;
		byte[] bite = new byte[1]; //using byte array here because for some reason doesn't work well if using readByte()
		int piece_index, block_offset, block_length, piece_length = peer.client.torrentInfo.piece_length;
		byte[] block;
		try {
			dis.read(bite);
			switch (bite[0]) {
				case 0:
					logger.info("Received {CHOKE} message from Peer: (" + peer.peerId + ").");
					peer.peerChoking = true;
					MyTools.saveDownloadedPieces(peer.client);
					break;
				case 1:
					logger.info("Received {UNCHOKE} message from Peer: (" + peer.peerId + ").");
					peer.peerChoking = false;
					break;
				case 2:
					logger.info("Received {INTERESTED} message from Peer: (" + peer.peerId + ").");
					peer.peerInterested = true;
					break;
				case 3:
					logger.info("Received {NOT_INTERESTED} message from Peer: (" + peer.peerId + ").");
					peer.peerInterested = false;
					break;
				case 4:
					logger.info("Received {HAVE} message from Peer: (" + peer.peerId + ").");
					//Message is a have message
					if (!peer.sentGreetings) {
			        	peer.sendMessage(Message.createUnchoke());
			        	peer.sendMessage(Message.createInterested());
			        	peer.sentGreetings = true;
					}
					//First, read the piece_index of the have message
					piece_index = dis.readInt();
					//Set value of the peerhavearray to true
					peer.peerHaveArray[piece_index] = true;
					//create a new request message to get the piece if client doesn't have it
					if (!peer.client.havePieces[piece_index])
						peer.sendMessage(createRequest(piece_index, 0, peer.client.torrentInfo.piece_length));
					//If the peer is a bad torrent controller, then need to check all of have messages to make it a seed
					if (peer.peerHaveArray.length == peer.client.numOfPieces) {
						if(!peer.isSeed) peer.isSeed = true;
					}
					break;
				case 5:
					logger.info("Received {BITFIELD} message from Peer: (" + peer.peerId + ").");
					//Message is a bitfield message (and therefore the peer is not a bad torrent controller)
					peer.badTorrentController = false;
					//Send unchoke and interested
					if (!peer.sentGreetings) {
					    peer.sendMessage(Message.createUnchoke());
					    peer.sendMessage(Message.createInterested());
					    peer.sentGreetings = true;
					}
					//Store the bitfield in the peer
					byte[] peerBitfield = new byte[length - 1];
					dis.read(peerBitfield);
					peer.peerBytefield = new boolean[peer.client.numOfPieces];
					int count = 0;
					for (int i = 0; i < peer.peerBytefield.length; i++) {
						if (MyTools.isBitSet(peerBitfield, i)) {
							peer.peerBytefield[i] = true;
							count++;
						}
					}
					peer.sizeOfBytefield = count;
					break;
				case 6:
					logger.info("Received {REQUEST} message from Peer: (" + peer.peerId + ").");
					//Message is a request message (if have it, send it)
					piece_index = dis.readInt();
					block_offset = dis.readInt();
					block_length = dis.readInt();
					if (peer.client.havePieces[piece_index]) {
						byte[] payload = new byte[block_length];
						payload = Arrays.copyOfRange(peer.client.rawFileBytes, 
								(piece_index * piece_length) + block_offset, 
								(piece_index + 1) * piece_length);
						peer.sendMessage(createPiece(piece_index, block_offset, payload));
						peer.client.bytesUploaded = peer.client.bytesUploaded + payload.length;
					}
					break;
				case 7:
					logger.info("Received {PIECE} message from Peer: (" + peer.peerId + ").");
					//Message is a piece message
					piece_index = dis.readInt();
					block_offset = dis.readInt();
					block = new byte[length - 9];
					dis.read(block);
					//peer.client.addToReceiveQueue(createPiece(piece_index, block_offset, block));
					MessageDigest md = MessageDigest.getInstance("SHA");
					byte[] hash = null;
					hash = md.digest(block);
					boolean cray = true;
					byte[] checkWith = peer.client.torrentInfo.piece_hashes[piece_index].array();
					for (int i = 0; i < checkWith.length; i++)
						if (checkWith[i] != hash[i]) cray = false;
					if (cray) {
						for (int i = (piece_index * piece_length) + block_offset; 
								i < (piece_index * piece_length) + block_offset + block.length; i++) {
							peer.client.rawFileBytes[i] = block[i-(piece_index * piece_length + block_offset)];
						}
						peer.client.bytesLeft = peer.client.bytesLeft - length + 9;
						peer.client.bytesDownloaded = peer.client.bytesDownloaded + length - 9;
						peer.client.havePieces[piece_index] = true;
						peer.client.numOfHavePieces++;
						peer.downloadedFromPeer++;
						peer.client.haveSendQueue.offer(createHave(piece_index), 2500, TimeUnit.MILLISECONDS);
					} else {
						peer.sendMessage(createRequest(piece_index, block_offset, length));
					}
					break;
				case 8:
					logger.info("Received {CANCEL} message from Peer: (" + peer.peerId + ").");
					// Message is a cancel message
					piece_index = dis.readInt();
					block_offset = dis.readInt();
					block_length = dis.readInt();
					// TODO
					/*byte[] payload = new byte[block_length];
					payload = Arrays.copyOfRange(
							peer.rawFileBytes, 
							(piece_index * peer.client.torrent_info.piece_length) + block_offset, 
							(piece_index + 1) * peer.client.torrent_info.piece_length);
					peer.removeFromSendQueue(createPiece(piece_index, block_offset, payload));
					break;*/
				default:
					break;
			}
		} catch(EOFException e) {
			logger.info("EOF exception from Peer : " + peer.peerId);
			peer.shutdown();
		} catch(IOException e) {
			logger.info("IO exception from Peer : " + peer.peerId);
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			logger.info("NoSuchAlgorithm exception from Peer : " + peer.peerId);
			e.printStackTrace();
		} catch (InterruptedException e) {
			logger.info("Interrupted exception from Peer : " + peer.peerId);
			e.printStackTrace();
		}
	}
	
	
	/**
	 * This method verifies that the peer id received in a handshake message matches with the peer id that was received in the
	 * tracker response that corresponds with the current peer's ip.
	 * 
	 * @param handshake -> The handshakeMessage that the peer sent
	 * @param peer_id -> The peer id that should be in the handshake method
	 * @return -> True if the peer id matches with the one in the message
	 */
	public static boolean verifyHandshake(Peer peer, byte[] handshake) {
		// TODO always returns false...
		if (handshake[0] != (byte) 19) return false;
		if (!(new String(Arrays.copyOfRange(handshake, 1, 20)).equals("BitTorrent Protocol"))) return false;
		if (!(new String(Arrays.copyOfRange(handshake, 20, 28)).equals(new String(new byte[8])))) return false;
		for (int i = 28; i < 48; i++) {
			if (handshake[i] != peer.infoHash[i-28]) return false;
		}
		return true;
	}
	
	
	/**
	 * Create a keep-alive message.
	 * @return 
	 */
	public static Message createKeepAlive() {
		return new Message((byte) -1);
	}
	
	
	/**
	 * Create a choke message.
	 * @return
	 */
	public static Message createChoke() {
		return new Message((byte) 0);
	}
	
	
	/**
	 * Create an unchoke message.
	 * @return
	 */
	public static Message createUnchoke() {
		return new Message((byte) 1);
	}
	
	
	/**
	 * Create an interested message.
	 * @return
	 */
	public static Message createInterested() {
		return new Message((byte) 2);
	}
	
	
	/**
	 * Create an uninterested message.
	 * @return
	 */
	public static Message createNotInterested() {
		return new Message((byte) 3);
	}
	
	
	/**
	 * Create a have message.
	 * @param piece_index - 0-based index of the piece I have (out of total number of pieces).
	 * @return
	 */
	public static Message createHave(int piece_index) {
		return new Message(5, (byte) 4, piece_index);
	}
	
	
	/**
	 * Create a bitfield message.
	 * @param client
	 * @return
	 */
	public static Message createBitfield(RUBTClient client) {
		if (client.havePieces == null) return null;
		// If the number of pieces is divisible by 8, make an array of size numOfPieces/8, else make one of size numOfPieces/8 + 1
		byte[] bitfield = 
				new byte[((double)client.numOfPieces)/8 == (double)(client.numOfPieces/8) ?
						client.numOfPieces/8 : client.numOfPieces/8 + 1];
		for (int i = 0; i < client.numOfPieces; i++) {
			if (client.havePieces[i]) {
				MyTools.setBit(bitfield, i);
			}
		}
		return new Message(bitfield.length + 1, (byte) 5, bitfield);
	}
	
	
	/**
	 * Create a request message.
	 * @param index - 0-based index of piece
	 * @param offset - 0-based index of block
	 * @param length - length of block
	 * @return
	 */
	public static Message createRequest(int index, int offset, int length) {
		int[] payload = {index, offset, length};
		//return new Message(13, (byte) 6, payload);
		Message message = new Message(13, (byte) 6, payload);
		return message;
	}
	
	
	/**
	 * Create a piece message.
	 * @param client
	 * @return
	 */
	public static Message createPiece(int piece_index, int block_offset, byte[] payload) {
		return new Message(piece_index, block_offset, payload);
	}
	
	
	public byte[] createCancel(RUBTClient client) {
		byte[] biteArray = null;
		// TODO
		return biteArray;
	}
}
