package net.sf.timeslottracker.integrations.issuetracker.jira;

import static net.sf.timeslottracker.integrations.issuetracker.jira.JiraTracker.JIRA_VERSION_6;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.ExecutorService;
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

	private static final DateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

	private static final String JIRA_DEFAULT_VERSION = JIRA_VERSION_6;

	protected final TimeSlotTracker timeSlotTracker;

	protected final ExecutorService executorService;

	/**
	 * JIRA password per application runtime session
	 */
	protected String sessionPassword = StringUtils.EMPTY;

	abstract void getFilterIssues(final String filterId, final IssueHandler handler)
		      throws IssueTrackerException;

	public abstract Issue getIssue(String key) throws IssueTrackerException;

	abstract void upsertTimeslot(final TimeSlot timeSlot) throws IssueTrackerException;

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
		for (Attribute attribute : task.getAttributes())
		{
			if (attribute.getAttributeType().equals(IssueKeyAttributeType.getInstance()))
			{
				return String.valueOf(attribute.get());
			}
		}

		return null;
	}
}
