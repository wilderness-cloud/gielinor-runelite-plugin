package cloud.tobbbles.gielinor.runelite.panel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel: link, the code to approve while linking, what is being sent, and unlink. It only
 * shows a {@link View}; the plugin builds the view and handles the buttons.
 */
public class GielinorPanel extends PluginPanel
{
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final int TEXT_WIDTH = 190;

	/** What the panel shows. Built by the plugin from the link and the pusher. */
	public static final class View
	{
		public enum Mode
		{
			UNLINKED, LINKING, LINKED, EXPIRED
		}

		public Mode mode = Mode.UNLINKED;
		/** The code to approve, while linking. */
		public String userCode;
		/** Linked, and the "Send to Gielinor" setting is on. */
		public boolean sending;
		/** The character being sent, e.g. "Zezima"; null while logged out or untracked. */
		public String character;
		/** When the relay last acknowledged a push, in ms; 0 if never. */
		public long lastAckAt;
		/** What went wrong, if anything. */
		public String problem;
	}

	public interface Actions
	{
		void link();

		void openApprovalPage();

		void cancelLinking();

		void unlink();

		void startSending();
	}

	private final Actions actions;
	private final JLabel status = new JLabel();
	private final JLabel code = new JLabel();
	private final JLabel detail = new JLabel();
	private final JLabel problem = new JLabel();
	private final JButton primary = new JButton();
	private final JButton secondary = new JButton();
	private Runnable primaryAction = () -> {};
	private Runnable secondaryAction = () -> {};

	public GielinorPanel(Actions actions)
	{
		this.actions = actions;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Gielinor");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
		code.setForeground(ColorScheme.TEXT_COLOR);
		problem.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		primary.addActionListener(e -> primaryAction.run());
		secondary.addActionListener(e -> secondaryAction.run());

		for (Component c : new Component[]{title, status, code, problem, detail, primary, secondary})
		{
			((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(c);
			content.add(Box.createVerticalStrut(8));
		}
		primary.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 30));
		secondary.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 30));

		add(content, BorderLayout.NORTH);
		show(new View());
	}

	/** Shows a view. Safe from any thread. */
	public void update(View view)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			show(view);
		}
		else
		{
			SwingUtilities.invokeLater(() -> show(view));
		}
	}

	private void show(View view)
	{
		code.setVisible(false);
		secondary.setVisible(false);
		primary.setVisible(true);
		problem.setVisible(view.problem != null);
		problem.setText(wrap(view.problem));

		switch (view.mode)
		{
			case LINKING:
				status.setText(wrap("Approve this code on the Gielinor site. The page has opened in your browser."));
				code.setText(view.userCode);
				code.setVisible(true);
				detail.setText(wrap("Waiting for you to approve it. You'll sign in there, not here: "
					+ "this plugin never sees a password."));
				button(primary, "Open the approval page again", actions::openApprovalPage);
				button(secondary, "Cancel", actions::cancelLinking);
				secondary.setVisible(true);
				break;
			case LINKED:
				if (view.sending)
				{
					status.setText(wrap(view.character != null
						? "Sending " + view.character + "'s progress to Gielinor."
						: "Linked. Sending starts when you log in."));
					detail.setText(wrap(view.lastAckAt > 0
						? "Last sent at " + TIME.format(Instant.ofEpochMilli(view.lastAckAt)) + "."
						: "Nothing sent yet."));
					primary.setVisible(false);
				}
				else
				{
					status.setText(wrap("Linked, but sending is switched off."));
					detail.setText(wrap("Turn on \"Send to Gielinor\" in this plugin's settings, or here."));
					button(primary, "Start sending", actions::startSending);
				}
				button(secondary, "Unlink", actions::unlink);
				secondary.setVisible(true);
				break;
			case EXPIRED:
				status.setText(wrap("The link to Gielinor has ended. Nothing is being sent."));
				detail.setText(wrap("It was removed on the Gielinor site, or not used for 90 days."));
				button(primary, "Link again", actions::link);
				break;
			default:
				status.setText(wrap("Link this RuneLite to your Gielinor account so the Gielinor app can "
					+ "track your goals against your real character."));
				detail.setText(wrap("Once linked, it sends your skills, quests, diaries, inventory, equipment "
					+ "and bank to Gielinor, where only you can see them. You can choose what's sent in the "
					+ "plugin's settings, and unlink at any time."));
				button(primary, "Link to Gielinor", actions::link);
				break;
		}
		revalidate();
		repaint();
	}

	private void button(JButton button, String text, Runnable action)
	{
		button.setText(text);
		if (button == primary)
		{
			primaryAction = action;
		}
		else
		{
			secondaryAction = action;
		}
	}

	private static String wrap(String text)
	{
		if (text == null)
		{
			return "";
		}
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><body style='width:" + TEXT_WIDTH + "px'>" + escaped + "</body></html>";
	}
}
