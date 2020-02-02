package net.sf.timeslottracker.integrations.issuetracker.jira;

import static net.sf.timeslottracker.integrations.issuetracker.jira.JiraClient.JIRA_DEFAULT_VERSION;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import net.sf.timeslottracker.core.Configuration;
import net.sf.timeslottracker.core.TimeSlotTracker;
import net.sf.timeslottracker.data.Attribute;
import net.sf.timeslottracker.data.TimeSlot;
import net.sf.timeslottracker.integrations.issuetracker.Issue;
import net.sf.timeslottracker.integrations.issuetracker.IssueHandler;
import net.sf.timeslottracker.integrations.issuetracker.IssueTrackerException;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogStatusType;

final class JiraClientImpl extends JiraClient
{
	private static final Logger LOG = Logger.getLogger(JiraClientV6.class.getName());

	private final TimeSlotTracker timeSlotTracker;

	private final ExecutorService executorService;

	private final String version;

	JiraClientImpl()
	{
	    this.version = timeSlotTracker.getConfiguration().get(Configuration.JIRA_VERSION, JIRA_DEFAULT_VERSION);
	}

	@Override
	void getFilterIssues(final String filterId, final IssueHandler handler) throws IssueTrackerException
	{
		// TODO Auto-generated method stub

	}

	@Override
	public Issue getIssue(String key) throws IssueTrackerException
	{
		try
		{
			key = prepareKey(key);
			if (key == null)
			{
				return null;
			}

			String urlString = MessageFormat.format(issueUrlTemplate, getBaseJiraUrl(), key, getAuthorizedParams());
			URL url = new URL(urlString);
			URLConnection connection = getUrlConnection(url);
			try
			{
				BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream()));
				String line = br.readLine();
				String id = null;
				String summary = null;
				while (line != null)
				{
					line = decodeString(line);
					Matcher matcherId = patternIssueId.matcher(line);
					if (id == null && matcherId.find())
					{
						id = matcherId.group(1);
						continue;
					}

					Matcher matcherSummary = patternSummary.matcher(line);
					if (summary == null && matcherSummary.find())
					{
						summary = matcherSummary.group(1);
						continue;
					}

					if (id != null && summary != null)
					{
						return new JiraIssue(key, id, summary);
					}

					line = br.readLine();
				}
			}
			finally
			{
				connection.getInputStream().close();
			}
			return null;
		}
		catch (FileNotFoundException e)
		{
			return null;
		}
		catch (IOException e)
		{
			throw new IssueTrackerException(e);
		}
	}

	@Override
	void upsertTimeslot(final TimeSlot timeSlot) throws IssueTrackerException
	{
		// getting issue key
		final String key = getIssueKey(timeSlot.getTask());
		if (key == null)
		{
			return;
		}

		LOG.info("Updating jira worklog for issue with key " + key + " ...");

		// analyze the existing worklog status and duration
		final long duration;
		final Attribute statusAttribute = getIssueWorkLogDuration(timeSlot);
		if (statusAttribute != null && !version.equals(JIRA_VERSION_6))
		{
			int lastDuration = Integer.parseInt(String.valueOf(statusAttribute.get()));
			if (timeSlot.getTime() <= lastDuration)
			{
				LOG.info("Skipped updating jira worklog for issue with key " + key
						+ ". Reason: current timeslot duration <= already saved in worklog");
				return;
			}

			duration = timeSlot.getTime() - lastDuration;

			LOG.info("Stop => timeSlot.getTime()=" + timeSlot.getTime() + " duration=" + duration + " lastDuration= "
					+ lastDuration);
		}
		else
		{
			duration = timeSlot.getTime();

			LOG.info("Stop => timeSlot.getTime()=" + timeSlot.getTime() + " duration=" + duration);
		}

		Runnable searchIssueTask = () ->
		{
			Issue issue = null;
			try
			{
				issue = getIssue(key);
			}
			catch (IssueTrackerException e2)
			{
				LOG.info(e2.getMessage());
			}
			if (issue == null)
			{
				LOG.info("Nothing updated. Not found issue with key " + key);
				return;
			}

			final String issueId = issue.getId();
			Runnable updateWorklogTask = () ->
			{
				try
				{
					addWorklog(timeSlot, key, issueId, statusAttribute, duration);
				}
				catch (IOException e)
				{
					final String start = formatDate(timeSlot.getStartDate());

					LOG.log(Level.WARNING, "Error occured while updating jira worklog for issue " + key + " (start="
							+ start + " duration=" + duration + ")", e);
				}
			};
			executorService.execute(updateWorklogTask);
		};
		executorService.execute(searchIssueTask);
	}

	private void addWorklog(
			final TimeSlot timeSlot,
			final String key,
			final String issueId,
			Attribute statusAttribute,
			long duration
	) throws IOException
	{
		final URL url = new URL(getBaseJiraUrl() + getAddWorklogPath(issueId));
		final URLConnection connection = getUrlConnection(url);

		if (connection instanceof HttpURLConnection)
		{
			HttpURLConnection httpConnection = (HttpURLConnection) connection;
			httpConnection.setRequestMethod("POST");
			httpConnection.setDoInput(true);
			httpConnection.setDoOutput(true);
			httpConnection.setUseCaches(false);
			httpConnection.setRequestProperty("Content-Type", getContentType());

			// sending data
			try (final OutputStreamWriter writer = new OutputStreamWriter(httpConnection.getOutputStream()))
			{
				String jiraDuration = (duration / 1000 / 60) + "m";
				LOG.finest(
						"addWorkLog => jiraDuration=" + jiraDuration + " started=" + formatDate(timeSlot.getStartDate())
								+ " comment=" + timeSlot.getDescription() + " toString=" + timeSlot.toString()
				);

				writer.append(getAuthorizedParams()).append(getPair("id", issueId))
						.append(getPair("comment", URLEncoder.encode(timeSlot.getDescription(), "UTF-8")))
						.append(getPair("worklogId", "")).append(getPair("timeLogged", jiraDuration)).append(
								getPair("startDate",
										URLEncoder.encode(new SimpleDateFormat("dd/MMM/yy KK:mm a")
												.format(timeSlot.getStartDate()), "UTF-8")))
						.append(getPair("adjustEstimate", "auto")).append(getPair("newEstimate", ""))
						.append(getPair("commentLevel", ""));
			}

			try (final BufferedReader br = new BufferedReader(
					new InputStreamReader(connection.getInputStream())))
			{
				final String line = br.readLine();

				LOG.finest("jira result: " + line);
			}

			if (statusAttribute == null)
			{
				statusAttribute = new Attribute(IssueWorklogStatusType.getInstance());
				final List<Attribute> list = new ArrayList<Attribute>(timeSlot.getAttributes());
				list.add(statusAttribute);
				timeSlot.setAttributes(list);
			}

			statusAttribute.set(timeSlot.getTime());

			LOG.info("Updated jira worklog with key: " + key);
		}
	}

	private URLConnection getUrlConnection(URL url) throws IOException
	{
		LOG.finest("Accessing : " + url);

		final URLConnection connection = url.openConnection();

		return connection;
	}

	private String getAddWorklogPath(String issueId)
	{
		final String path;

		if (version.equals(JIRA_VERSION_3))
		{
			path = "/secure/LogWork.jspa";
		}
		else if (version.equals(JIRA_VERSION_310))
		{
			path = "/secure/CreateWorklog.jspa";
		}
		else
		{
			throw new IllegalStateException("Should never get here.");
		}

		return path;
	}

	private String getAuthorizedParams()
	{
		return "os_username=" + getLogin() + getPair("os_password", getPassword());
	}

	private String getPair(String name, String value)
	{
		return "&" + name + "=" + value;
	}

	private String getContentType()
	{
		return "application/x-www-form-urlencoded";
	}
}
