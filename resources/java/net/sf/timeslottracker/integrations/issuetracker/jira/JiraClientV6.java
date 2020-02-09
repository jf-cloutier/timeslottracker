package net.sf.timeslottracker.integrations.issuetracker.jira;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.binary.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import net.sf.timeslottracker.core.TimeSlotTracker;
import net.sf.timeslottracker.data.Attribute;
import net.sf.timeslottracker.data.Task;
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

	JiraClientV6(final TimeSlotTracker tst)
	{
		super(tst);
	}

	@Override
	void getFilterIssues(final String filter, final IssueHandler handler) throws IssueTrackerException
	{
		try
		{
			final String jql;

			if (filter.matches("^(?:-1|\\d+)$"))
			{
				jql = fetchJql(filter);
			}
			else
			{
				jql = filter;
			}

			final URL url = new URL(getBaseJiraUrl() + "/rest/api/2/search");
			final HttpURLConnection conn = getUrlConnection(url);

			conn.setRequestMethod("POST");
			conn.setDoInput(true);
			conn.setDoOutput(true);
			conn.setUseCaches(false);
			conn.setRequestProperty("Content-Type", getContentType());

			// sending data
			try (final OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))
			{
				final JsonArray jsonArr = new JsonArray();

				jsonArr.add("id");
				jsonArr.add("key");
				jsonArr.add("summary");

				final JsonObject jsonObj = new JsonObject();

				jsonObj.addProperty("jql", jql);
				jsonObj.addProperty("startAt", 0);
				jsonObj.addProperty("maxResults", 100);
				jsonObj.add("fields", jsonArr);

				writer.write(jsonObj.toString());
			}

			try (final Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
			{
				final JsonObject resp = JsonParser.parseReader(reader).getAsJsonObject();
				final JsonElement issues = resp.get("issues");

				if (issues == null || !issues.isJsonArray())
					return;

				for (final JsonElement item : issues.getAsJsonArray())
				{
					final JsonObject issue = item.getAsJsonObject();

					handler.handle(createIssue(issue));
				}
			}
		}
		catch (final Exception e)
		{
			throw new IssueTrackerException(e);
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

			jsonObject.addProperty("adjustEstimate", "auto");
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
	Issue getIssue(final String key) throws IssueTrackerException
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
				issue.get("fields").getAsJsonObject().get("summary").getAsString()
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

	private String fetchJql(final String filterId) throws IOException
	{
		final URL url = new URL(getBaseJiraUrl() + "/rest/api/2/filter/" + filterId);
		final HttpURLConnection conn = getUrlConnection(url);

		conn.setDoInput(true);
		conn.setUseCaches(false);
		conn.setRequestProperty("Content-Type", getContentType());

		try (final Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
		{
			final JsonObject resp = JsonParser.parseReader(reader).getAsJsonObject();

			return resp.get("jql").getAsString();
		}
	}

	@Override
	void validateFailed() {
		final List<Task> jiraTasks = getAllJiraTasks();
		final Map<String, UnsavedWorkLog> unsaved = new TreeMap<>();

		for (final Task jiraTask : jiraTasks) {
			final List<WorkLog> worklogs = getWorklogs(jiraTask);
			final List<TimeSlot> missingTimeSlots = getMissingTimeSlots(jiraTask, worklogs);

			if (!missingTimeSlots.isEmpty()) {
				unsaved.put(getIssueKey(jiraTask), new UnsavedWorkLog(jiraTask, missingTimeSlots));
			}
		}

		if (unsaved.isEmpty())
			return;

		final StringBuilder sb = new StringBuilder();

		sb.append(unsaved.size()).append(" issues are missing timeslots");

		for (final Map.Entry<String, UnsavedWorkLog> cur : unsaved.entrySet())
		{
			final List<TimeSlot> timeslots = cur.getValue().geTimeSlots();

			sb.append("\r\n\t").append(cur.getKey()).append(" is missing ").append(timeslots.size());
			sb.append(timeslots.size() == 1 ? " timeslot" : " timeslots");

			for (final TimeSlot ts : timeslots)
			{
				final long elapsed = (ts.getStopDate().getTime() - ts.getStartDate().getTime()) / 60000L;

				sb.append("\r\n\t\t").append(ts.getStartDate());
				sb.append(" ").append(elapsed).append(" minutes -- ");
				sb.append(ts.getDescription());
			}
		}

		LOG.warning(sb.toString());
	}

	private List<WorkLog> getWorklogs(final Task jiraTask) {
		final List<WorkLog> rtrn = new ArrayList<>();
		final String issueKey = getIssueKey(jiraTask);

		try {
			final URL url = new URL(getBaseJiraUrl() + getAddWorklogPath(issueKey));
			final HttpURLConnection httpConnection = getUrlConnection(url);

			httpConnection.setRequestMethod("GET");
			httpConnection.setDoInput(true);
			httpConnection.setDoOutput(false);
			httpConnection.setUseCaches(false);
			httpConnection.setRequestProperty("Content-Type", "application/json");

			try (final InputStream is = httpConnection.getInputStream();
					final JsonReader jr = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				jr.beginObject();

				while (jr.hasNext() && JsonToken.END_OBJECT != jr.peek()) {
					final String name = jr.nextName();

					if ("worklogs".contentEquals(name)) {
						jr.beginArray();

						while (jr.hasNext() && JsonToken.END_ARRAY != jr.peek()) {
							rtrn.add(parseWorkLog(jr));
						}

						jr.endArray();
					} else {
						jr.skipValue();
					}
				}

				jr.endObject();
			}
		} catch (final Exception e) {
			LOG.log(Level.WARNING, "Problem while retrieving work logs for Jira issue " + issueKey, e);
		}

		return rtrn;
	}

	private WorkLog parseWorkLog(final JsonReader jr) throws IOException, ParseException {
		String id = null;
		long timeSpentSeconds = -1L;
		Date started = null;

		jr.beginObject();

		while (jr.hasNext()) {
			final JsonToken token = jr.peek();

			if (JsonToken.END_OBJECT == token)
				break;

			final String name = jr.nextName();

			switch (name) {
			case "id":
				id = jr.nextString();
				break;
			case "timeSpentSeconds":
				timeSpentSeconds = jr.nextLong();
				break;
			case "started":
				synchronized (TIMESTAMP) {
					started = TIMESTAMP.parse(jr.nextString());
				}
				break;
			default:
				jr.skipValue();
				break;
			}
		}

		jr.endObject();

		if (id == null || started == null || timeSpentSeconds < 0)
			throw new IOException("WorkLog is missing information.");

		return new WorkLog(id, started, timeSpentSeconds);
	}

	private List<TimeSlot> getMissingTimeSlots(final Task jiraTask, final List<WorkLog> worklogs) {
		final Collection<TimeSlot> timeslots = jiraTask.getTimeslots();
		final List<TimeSlot> missingTimeslots = new ArrayList<>();

		if (timeslots != null)
			for (final TimeSlot timeslot : timeslots) {
				final long timeSpentInSeconds = getTimeSpentInSeconds(timeslot);

				if (timeSpentInSeconds < 0L) {
					missingTimeslots.add(timeslot);
				} else {
					final WorkLog worklog = findWorkLog(timeslot, worklogs);

					if (worklog == null) {
						missingTimeslots.add(timeslot);
					}
				}
			}

		return missingTimeslots;
	}

	private WorkLog findWorkLog(final TimeSlot timeslot, final List<WorkLog> worklogs) {
		final Date start = timeslot.getStartDate();

		for (final WorkLog worklog : worklogs) {
			if (Math.abs(worklog.start.getTime() - start.getTime()) < 5000L) {
				final long timeSpentSeconds = getTimeSpentInSeconds(timeslot);

				if (timeSpentSeconds >= 0 && timeSpentSeconds != worklog.timeSpentSeconds) {
					final String dateStr = formatDate(worklog.start);
					final String cmp = timeSpentSeconds < worklog.timeSpentSeconds ? "much" : "little";

					LOG.warning("Work Log time spent does not match for " + getIssueKey(timeslot.getTask()) + " ("
							+ timeSpentSeconds + " vs " + worklog.timeSpentSeconds + " at " + dateStr
							+ " -- Jira has too " + cmp + ")");
				}

				return worklog;
			}
		}

		return null;
	}

	private static final class WorkLog {
		private final Date start;
		private final long timeSpentSeconds;

		private WorkLog(final String id, final Date start, final long duration) {
			this.start = start;
			this.timeSpentSeconds = duration;
		}
	}

	private static final class UnsavedWorkLog {
		private final List<TimeSlot> timeSlots;
		UnsavedWorkLog(final Task task, final List<TimeSlot> unsavedWorkLogs) {
			this.timeSlots = unsavedWorkLogs;
		}

		List<TimeSlot> geTimeSlots() {
			return timeSlots;
		}
	}
}
