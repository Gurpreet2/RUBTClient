import java.util.Timer;
import java.util.TimerTask;
import java.awt.*;
import javax.swing.*;


public class Gui extends JFrame{
	private Timer timer;
	private RUBTClient client;
	private JButton exit;
	JProgressBar progbar;

	public Gui(RUBTClient rubt){
		super("RUBTClient");
		this.client = rubt;
		setLayout(new FlowLayout());
		//Progress bar set up
		progbar = new JProgressBar();
		progbar.setMaximum(100);
		progbar.setMinimum(0);
		progbar.setStringPainted(true);
		progbar.setValue(this.client.getProgressBarPercent());
		this.setContentPane(progbar);
		this.exit = new JButton("EXIT");
		add(this.exit);
		

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
