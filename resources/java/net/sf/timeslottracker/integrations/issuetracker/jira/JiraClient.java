package net.sf.timeslottracker.integrations.issuetracker.jira;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JOptionPane;

import net.sf.timeslottracker.core.Configuration;
import net.sf.timeslottracker.core.TimeSlotTracker;
import net.sf.timeslottracker.data.Attribute;
import net.sf.timeslottracker.data.AttributeType;
import net.sf.timeslottracker.data.Task;
import net.sf.timeslottracker.data.TimeSlot;
import net.sf.timeslottracker.integrations.issuetracker.Issue;
import net.sf.timeslottracker.integrations.issuetracker.IssueHandler;
import net.sf.timeslottracker.integrations.issuetracker.IssueKeyAttributeType;
import net.sf.timeslottracker.integrations.issuetracker.IssueTrackerException;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogStatusType;
import net.sf.timeslottracker.utils.StringUtils;

abstract class JiraClient
{
	private static final Logger LOG = Logger.getLogger(JiraClient.class.getName());

	protected static final DateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

	protected final TimeSlotTracker timeSlotTracker;

	protected final ExecutorService executorService;

	/**
	 * JIRA password per application runtime session
	 */
	private String sessionPassword = StringUtils.EMPTY;

	JiraClient(final TimeSlotTracker tst)
	{
		timeSlotTracker = tst;
	    executorService = Executors.newSingleThreadExecutor();

	    Runtime.getRuntime().addShutdownHook(new Thread() { @Override public void run() { executorService.shutdown(); } });
	}

	abstract void getFilterIssues(final String filterId, final IssueHandler handler)
		      throws IssueTrackerException;

	abstract Issue getIssue(String key) throws IssueTrackerException;

	abstract void upsertTimeslot(final TimeSlot timeSlot) throws IssueTrackerException;

	abstract void validateFailed();

	URI getIssueUrl(Task task) throws IssueTrackerException {
		String issueKey = getIssueKey(task);

		if (issueKey == null) {
			throw new IssueTrackerException(
					"Given task \"" + task.getName() + "\" is not issue task (i.e. does not has issue key attribute)");
		}

		String uriStr = getBaseJiraUrl() + "/browse/" + issueKey;
		try {
			return new URI(uriStr);
		} catch (URISyntaxException e) {
			throw new IssueTrackerException("Error occured while creating uri: " + uriStr);
		}
	}

	protected String getBaseJiraUrl()
	{
		String url = this.timeSlotTracker.getConfiguration().getString(Configuration.JIRA_URL, "");

		// truncate symbol / if present
		if (url.endsWith("/"))
		{
			url = url.substring(0, url.length() - 1);
		}

		return url;
	}

	protected String getLogin()
	{
		return timeSlotTracker.getConfiguration().getString(Configuration.JIRA_LOGIN, "");
	}

	protected String getPassword()
	{
		final String password = timeSlotTracker.getConfiguration().getString(Configuration.JIRA_PASSWORD, null);

		if (!StringUtils.isBlank(password))
		{
			return password;
		}

		synchronized(this)
		{
			if (StringUtils.isBlank(sessionPassword))
			{
				sessionPassword = JOptionPane.showInputDialog(timeSlotTracker.getRootFrame(),
						timeSlotTracker.getString("issueTracker.credentialsInputDialog.password"));
			}

			return sessionPassword;
		}
	}

	static String prepareKey(String key)
	{
		if (key == null)
		{
			return null;
		}

		return key.trim().toUpperCase();
	}

	protected static String formatDate(final Date d)
	{
		synchronized (TIMESTAMP)
		{
			return TIMESTAMP.format(d);
		}
	}

	protected Attribute getIssueWorkLogDuration(final TimeSlot timeSlot)
	{
		return getAttribute(timeSlot, IssueWorklogStatusType.getInstance());
	}

	protected Attribute getAttribute(final TimeSlot timeSlot, final AttributeType attrType)
	{
		for (Attribute attribute : timeSlot.getAttributes())
		{
			if (attribute.getAttributeType().equals(attrType))
			{
				return attribute;
			}
		}

		return null;
	}

	protected Attribute getOrCreate(final TimeSlot timeslot, final AttributeType attrType)
	{
		final Collection<Attribute> attrs = timeslot.getAttributes();

		synchronized (attrs)
		{
			if (attrs != null)
				for (final Attribute attr : attrs)
				{
					if (attr.getAttributeType().equals(attrType))
						return attr;
				}

			final Attribute attr = new Attribute(attrType);

			attrs.add(attr);

			return attr;
		}
	}

	protected String getIssueKey(Task task)
	{
		if (task == null)
			return null;

		for (Attribute attribute : task.getAttributes())
		{
			if (attribute.getAttributeType().equals(IssueKeyAttributeType.getInstance()))
			{
				return String.valueOf(attribute.get());
			}
		}

		return null;
	}

	protected long getTimeSpentInSeconds(final TimeSlot timeslot) {
		final Attribute attr = getIssueWorkLogDuration(timeslot);

		if (attr == null)
			return -1L;

		return Long.parseLong(String.valueOf(attr.get())) / 1000L;
	}

	void fixFailed() {
		final List<Task> jiraTasks = getAllJiraTasks();

		for (final Task jiraTask : jiraTasks) {
			final Collection<TimeSlot> timeslots = jiraTask.getTimeslots();

			if (timeslots != null)
				for (final TimeSlot timeslot : timeslots) {
					final long timeSpentInSeconds = getTimeSpentInSeconds(timeslot);

					if (timeSpentInSeconds < 0L) {
						try {
							upsertTimeslot(timeslot);
						} catch (IssueTrackerException e) {
							LOG.log(Level.WARNING,
									"Problem rescheduling Jira update for " + getIssueKey(timeslot.getTask()), e);
						}
					}
				}
		}
	}

	protected List<Task> getAllJiraTasks() {
		final List<Task> jiraTasks = new ArrayList<>();

		findAllJiraTasks(jiraTasks, timeSlotTracker.getDataSource().getRoot());

		return jiraTasks;
	}

	private void findAllJiraTasks(final List<Task> jiraTasks, final Task task) {
		final String key = getIssueKey(task);

		if (key != null) {
			jiraTasks.add(task);
		}

		final Collection<Task> children = task.getChildren();

		if (children != null)
			for (final Task child : children) {
				findAllJiraTasks(jiraTasks, child);
			}
	}
}
