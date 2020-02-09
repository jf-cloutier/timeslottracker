package net.sf.timeslottracker.integrations.issuetracker.jira;

import java.net.URI;
import java.util.logging.Logger;

import net.sf.timeslottracker.core.Action;
import net.sf.timeslottracker.core.Configuration;
import net.sf.timeslottracker.core.TimeSlotTracker;
import net.sf.timeslottracker.data.DataLoadedListener;
import net.sf.timeslottracker.data.Task;
import net.sf.timeslottracker.data.TimeSlot;
import net.sf.timeslottracker.data.TimeSlotChangedListener;
import net.sf.timeslottracker.integrations.issuetracker.Issue;
import net.sf.timeslottracker.integrations.issuetracker.IssueHandler;
import net.sf.timeslottracker.integrations.issuetracker.IssueTracker;
import net.sf.timeslottracker.integrations.issuetracker.IssueTrackerException;

/**
 * Implementation of Issue Tracker for Jira
 * https://docs.atlassian.com/software/jira/docs/api/REST/7.1.2/
 * https://ecosystem.atlassian.net/wiki/spaces/JRJC/overview
 * <p>
 * JIRA (R) Issue tracking project management software
 * (http://www.atlassian.com/software/jira)
 */
public class JiraTracker implements IssueTracker {

	public static final String JIRA_VERSION_6 = "6";
	public static final String JIRA_VERSION_310 = "3.10";
	public static final String JIRA_VERSION_3 = "3";
	private static final String JIRA_DEFAULT_VERSION = JIRA_VERSION_6;

	private static final Logger LOG = Logger.getLogger(JiraTracker.class.getName());

	private final TimeSlotTracker timeSlotTracker;
	
	private final JiraClient jiraClient;

	public JiraTracker(final TimeSlotTracker timeSlotTracker) {
		this.timeSlotTracker = timeSlotTracker;

		final String version = timeSlotTracker.getConfiguration().get(Configuration.JIRA_VERSION, JIRA_DEFAULT_VERSION);
		
		if (version.equals(JIRA_VERSION_6))
			jiraClient = new JiraClientV6(timeSlotTracker);
		else
			jiraClient = new JiraClientImpl(timeSlotTracker, version);

		this.timeSlotTracker.addActionListener((DataLoadedListener) action -> init());
	}

	@Override
	public void add(final TimeSlot timeSlot) throws IssueTrackerException {
		jiraClient.upsertTimeslot(timeSlot);
	}

	@Override
	public Issue getIssue(final String key) throws IssueTrackerException {
		return jiraClient.getIssue(key);
	}

	@Override
	public URI getIssueUrl(final Task task) throws IssueTrackerException {
		return jiraClient.getIssueUrl(task);
	}

	@Override
	public void getFilterIssues(final String filterId, final IssueHandler handler) throws IssueTrackerException {
		jiraClient.getFilterIssues(filterId, handler);
	}

	@Override
	public boolean isIssueTask(final Task task) {
		return jiraClient.getIssueKey(task) != null;
	}

	@Override
	public boolean isValidKey(final String key) {
		final String preparedKey = JiraClient.prepareKey(key);
		return preparedKey != null && preparedKey.matches("[a-z,A-Z0-9]+-[0-9]+");
	}

	private void init() {
		jiraClient.fixFailed();

		if (Boolean.getBoolean("validate-failed"))
			jiraClient.validateFailed();

		// updates when timeslot changed
		this.timeSlotTracker.getLayoutManager().addActionListener(new TimeSlotChangedListener() {
			@Override
			public void actionPerformed(Action action) {
				Boolean enabled = timeSlotTracker.getConfiguration().getBoolean(Configuration.JIRA_ENABLED, false);

				if (!enabled) {
					return;
				}

				if (!action.getName().equalsIgnoreCase("TimeSlotChanged")) {
					return;
				}

				// no active timeSlot
				TimeSlot timeSlot = (TimeSlot) action.getParam();
				if (timeSlot == null) {
					return;
				}

				boolean isNullStart = timeSlot.getStartDate() == null;
				boolean isNullStop = timeSlot.getStopDate() == null;

				// paused timeSlot
				if (isNullStart && isNullStop) {
					return;
				}

				// started timeSlot
				if (isNullStop) {
					return;
				}

				// removed timeSlot
				if (timeSlot.getTask() == null) {
					return;
				}

				// stopped or edited task
				try {
					add(timeSlot);
				} catch (IssueTrackerException e) {
					LOG.warning(e.getMessage());
				}
			}
		});
	}
}
