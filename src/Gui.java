import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.awt.*;
import javax.swing.*;



public class Gui extends JFrame{
	private Timer timer;
	private RUBTClient client;
	JProgressBar progbar;
	JTable table;
	JPanel panel;
	JLabel label;
	public Gui(RUBTClient rubt){
		setTitle("RUBTClient");
		panel = new JPanel();
		panel.setPreferredSize(new Dimension(300, 100));
		getContentPane().add(panel);	
		this.client = rubt;
		//Progress bar set up
		label = new JLabel("Download Progress...");
		label.setPreferredSize(new Dimension(280, 24));
		panel.add(label);
		progbar = new JProgressBar();
		progbar.setPreferredSize( new Dimension( 300, 20 ) );
		progbar.setMaximum(100);
		progbar.setMinimum(0);
		progbar.setValue(this.client.getProgressBarPercent());
		progbar.setStringPainted(true);
		progbar.setBounds(20, 35, 260, 20);
		panel.add(progbar);
		String[] columnNames = {"Peer IP", "Port", "Choked By Client?"};
		ArrayList<Peer> peers = client.peers;
		int peer_index = 0;
		Object[][] data = new Object[peers.size()][3];
		for(int row=0; row < peers.size(); row++){
			for(int col=0; col < 3; col++){
				switch(col){
					case 0:
						data[row][col] = peers.get(peer_index).peerIp;
						break;
					case 1:
						data[row][col] = new Integer(6881);
						break;
					case 2:
						data[row][col] = peers.get(peer_index).amChoking;
						break;
				}
			}
			peer_index++;
		}
		table = new JTable(data, columnNames);
		panel.add(new JScrollPane(this.table));
		//Make a timer for progress bar updates
		this.timer = new Timer(true);
		this.timer.scheduleAtFixedRate(new TimerTask(){
			// Update progress bar every 2 seconds
			@Override
			public void run() {
				progbar.setValue(client.getProgressBarPercent() * 100);
			}
			
		}, 1, 2000);
		
		
		
	}


}
