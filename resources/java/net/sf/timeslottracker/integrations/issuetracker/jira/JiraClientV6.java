package net.sf.timeslottracker.integrations.issuetracker.jira;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.binary.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.sf.timeslottracker.data.Attribute;
import net.sf.timeslottracker.data.TimeSlot;
import net.sf.timeslottracker.integrations.issuetracker.Issue;
import net.sf.timeslottracker.integrations.issuetracker.IssueHandler;
import net.sf.timeslottracker.integrations.issuetracker.IssueTrackerException;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogIdType;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogStatusType;

/**
 * https://docs.atlassian.com/software/jira/docs/api/REST/7.1.2/
 * @author jf.cloutier
 *
 */
final class JiraClientV6 extends JiraClient
{
	private static final Logger LOG = Logger.getLogger(JiraClientV6.class.getName());

	@Override
	void getFilterIssues(final String filter, final IssueHandler handler) throws IssueTrackerException
	{
		final String jql;

		if (filter.matches("^(?:-1|\\d+)$"))
		{
			final JsonObject resp = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();

			jql = resp.get("jql").getAsString();
		}
		else
		{
			jql = filter;
		}

		final JsonArray jsonArr = new JsonArray();

		jsonArr.add("id");
		jsonArr.add("key");
		jsonArr.add("summary");

		final JsonObject jsonObj = new JsonObject();

		jsonObj.addProperty("jql", jql);
		jsonObj.addProperty("startAt", 0);
		jsonObj.addProperty("maxResults", 100);
		jsonObj.add("fields", jsonArr);

		///

		final JsonObject resp = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
		final JsonElement issues = resp.get("issues");

		if (issues == null || !issues.isJsonArray())
			return;

		for (final JsonElement item : issues.getAsJsonArray())
		{
			final JsonObject issue = item.getAsJsonObject();

			handler.handle(createIssue(issue));
		}
	}

	@Override
	void upsertTimeslot(final TimeSlot timeSlot) throws IssueTrackerException
	{
		final String key = getIssueKey(timeSlot.getTask());

		if (key == null)
		{
			// This is not a Jira task.
			return;
		}

		LOG.info("Updating jira worklog for issue with key " + key + " ...");

		Runnable updateWorklogTask = () ->
		{
			try
			{
				addWorklog(timeSlot, key);
			}
			catch (IOException e)
			{
				final String start = formatDate(timeSlot.getStartDate());

				LOG.log(Level.WARNING, "Error occured while updating jira worklog for issue " + key + " (start="
						+ start + " duration=" + timeSlot.getTime() + ")", e);
			}
		};

		executorService.execute(updateWorklogTask);
	}

	private void addWorklog(final TimeSlot timeSlot, final String key) throws IOException
	{
		final StringBuilder sb = new StringBuilder();

		sb.append(getBaseJiraUrl()).append(getAddWorklogPath(key));

		final Attribute worklogId = getAttribute(timeSlot, IssueWorklogIdType.getInstance());
		String requestMethod = "POST";

		if (worklogId != null)
		{
			sb.append('/').append(worklogId.get());
			requestMethod = "PUT";
		}

		final URL url = new URL(sb.toString());
		final HttpURLConnection conn = getUrlConnection(url);

		conn.setRequestMethod(requestMethod);
		conn.setDoInput(true);
		conn.setDoOutput(true);
		conn.setUseCaches(false);
		conn.setRequestProperty("Content-Type", getContentType());

		// sending data
		try (final OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))
		{
			final long duration = timeSlot.getTime();
			final JsonObject jsonObject = new JsonObject();

			jsonObject.addProperty("timeSpentSeconds", duration / 1000L);
			jsonObject.addProperty("started", formatDate(timeSlot.getStartDate()));
			jsonObject.addProperty("comment", timeSlot.getDescription());

			final String json = jsonObject.toString();

			writer.append(json);
		}

		try (final Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
		{
			final JsonObject jsonObj = JsonParser.parseReader(reader).getAsJsonObject();
			final String id = jsonObj.get("id").getAsString();

			getOrCreate(timeSlot, IssueWorklogIdType.getInstance()).set(id);

			LOG.finest("jira result: " + jsonObj.toString());
		}

		getOrCreate(timeSlot, IssueWorklogStatusType.getInstance()).set(timeSlot.getTime());

		LOG.info("Updated jira worklog with key: " + key);
	}

	@Override
	public Issue getIssue(final String key) throws IssueTrackerException
	{
		try
		{
			final URL url = new URL(getBaseJiraUrl() + "/rest/api/2/issue/" + key);
			final HttpURLConnection conn = getUrlConnection(url);

			conn.setUseCaches(false);
			conn.setRequestProperty("Content-Type", getContentType());

			final JsonObject issue;

			try (final Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
			{
				issue = JsonParser.parseReader(reader).getAsJsonObject();
			}

			LOG.finest("jira result: " + issue.toString());

			return createIssue(issue);
		}
		catch (final Exception e)
		{
			throw new IssueTrackerException(e);
		}
	}

	private Issue createIssue(final JsonObject issue)
	{
		// TODO what about isSubTask?

		return new JiraIssue(
				issue.get("key").getAsString(),
				issue.get("id").getAsString(),
				issue.get("summary").getAsString()
		);
	}

	private HttpURLConnection getUrlConnection(URL url) throws IOException
	{
		LOG.finest("Accessing : " + url);

		final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		final String basicAuth = "Basic " + new String(new Base64().encode((getLogin() + ":" + getPassword()).getBytes()));

		connection.setRequestProperty("Authorization", basicAuth);

		return connection;
	}

	private String getAddWorklogPath(String issueId)
	{
		return "/rest/api/2/issue/" + issueId + "/worklog";
	}

	private String getContentType()
	{
		return "application/json";
	}
}
