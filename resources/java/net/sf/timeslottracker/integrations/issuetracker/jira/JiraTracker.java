package net.sf.timeslottracker.integrations.issuetracker.jira;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.codec.binary.Base64;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import net.sf.timeslottracker.core.Action;
import net.sf.timeslottracker.core.Configuration;
import net.sf.timeslottracker.core.TimeSlotTracker;
import net.sf.timeslottracker.data.Attribute;
import net.sf.timeslottracker.data.AttributeType;
import net.sf.timeslottracker.data.DataLoadedListener;
import net.sf.timeslottracker.data.Task;
import net.sf.timeslottracker.data.TimeSlot;
import net.sf.timeslottracker.data.TimeSlotChangedListener;
import net.sf.timeslottracker.integrations.issuetracker.Issue;
import net.sf.timeslottracker.integrations.issuetracker.IssueHandler;
import net.sf.timeslottracker.integrations.issuetracker.IssueKeyAttributeType;
import net.sf.timeslottracker.integrations.issuetracker.IssueTracker;
import net.sf.timeslottracker.integrations.issuetracker.IssueTrackerException;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogIdType;
import net.sf.timeslottracker.integrations.issuetracker.IssueWorklogStatusType;
import net.sf.timeslottracker.utils.StringUtils;

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

  private static final Logger LOG = Logger
      .getLogger(JiraTracker.class.getName());
  private static String decodeString(String s) {
    Pattern p = Pattern.compile("&#([\\d]+);");
    Matcher m = p.matcher(s);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      m.appendReplacement(sb,
          new String(Character.toChars(Integer.parseInt(m.group(1)))));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String prepareKey(String key) {
    if (key == null) {
      return null;
    }

    return key.trim().toUpperCase();
  }

  private final SAXParserFactory saxFactory;

  private final ExecutorService executorService;

  private final IssueKeyAttributeType issueKeyAttributeType;

  private final IssueWorklogStatusType issueWorklogStatusType;

  private final Pattern patternIssueId = Pattern
      .compile("<key id=\"([0-9]+)\">([\\d,\\s\u0021-\u0451]+)<");

  private final Pattern patternSummary = Pattern
      .compile("<summary>([\\d,\\s\u0021-\u0451]+)<");

  /**
   * JIRA password per application runtime session
   */
  private String sessionPassword = StringUtils.EMPTY;

  private final TimeSlotTracker timeSlotTracker;

  private final String issueUrlTemplate;
  private final String filterUrlTemplate;

  private final String version;

  public JiraTracker(final TimeSlotTracker timeSlotTracker) {
    this.timeSlotTracker = timeSlotTracker;
    this.executorService = Executors.newSingleThreadExecutor();

    this.issueKeyAttributeType = IssueKeyAttributeType.getInstance();
    this.issueWorklogStatusType = IssueWorklogStatusType.getInstance();

    this.issueUrlTemplate = timeSlotTracker.getConfiguration().get(
        Configuration.JIRA_ISSUE_URL_TEMPLATE,
        "{0}/si/jira.issueviews:issue-xml/{1}/?{2}");

    this.version = timeSlotTracker.getConfiguration()
        .get(Configuration.JIRA_VERSION, JIRA_DEFAULT_VERSION);

    this.filterUrlTemplate = timeSlotTracker.getConfiguration().get(
        Configuration.JIRA_FILTER_URL_TEMPLATE,
        "{0}/sr/jira.issueviews:searchrequest-xml/{1}/SearchRequest-{1}.xml?tempMax=1000&{2}");

    this.timeSlotTracker.addActionListener((DataLoadedListener) action -> init());

    this.saxFactory = SAXParserFactory.newInstance();
  }

  @Override
public void add(final TimeSlot timeSlot) throws IssueTrackerException {
    // getting issue key
    final String key = getIssueKey(timeSlot.getTask());
    if (key == null) {
      return;
    }

    LOG.info("Updating jira worklog for issue with key " + key + " ...");

    // analyze the existing worklog status and duration
    final long duration;
    final Attribute statusAttribute = getIssueWorkLogDuration(timeSlot);
    if (statusAttribute != null && !version.equals(JIRA_VERSION_6)) {
      int lastDuration = Integer
          .parseInt(String.valueOf(statusAttribute.get()));
      if (timeSlot.getTime() <= lastDuration) {
        LOG.info("Skipped updating jira worklog for issue with key " + key
            + ". Reason: current timeslot duration <= already saved in worklog");
        return;
      }

      duration = timeSlot.getTime() - lastDuration;

      LOG.info("Stop => timeSlot.getTime()=" + timeSlot.getTime() + " duration=" + duration + " lastDuration= " + lastDuration);
    } else {
      duration = timeSlot.getTime();

      LOG.info("Stop => timeSlot.getTime()=" + timeSlot.getTime() + " duration=" + duration);
    }

    Runnable searchIssueTask = () -> {
      Issue issue = null;
      try {
        issue = getIssue(key);
      } catch (IssueTrackerException e2) {
        LOG.info(e2.getMessage());
      }
      if (issue == null) {
        LOG.info("Nothing updated. Not found issue with key " + key);
        return;
      }

      final String issueId = issue.getId();
      Runnable updateWorklogTask = () -> {
        try {
          addWorklog(timeSlot, key, issueId, statusAttribute,
              duration);
        } catch (IOException e) {
        	final String start = formatDate(timeSlot.getStartDate());

          LOG.log(Level.WARNING, "Error occured while updating jira worklog for issue " + key +
        		  " (start=" + start + " duration=" + duration + ")", e);
        }
      };
      executorService.execute(updateWorklogTask);
    };
    executorService.execute(searchIssueTask);
  }

  private Attribute getIssueWorkLogDuration(final TimeSlot timeSlot) {
    return getAttribute(timeSlot, issueWorklogStatusType);
  }

  private Attribute getAttribute(final TimeSlot timeSlot, final AttributeType attrType) {
	    for (Attribute attribute : timeSlot.getAttributes()) {
	      if (attribute.getAttributeType().equals(attrType)) {
	        return attribute;
	      }
	    }

	    return null;
	  }

  private long getTimeSpentInSeconds(final TimeSlot timeslot)
  {
	  final Attribute attr = getIssueWorkLogDuration(timeslot);

	  if (attr == null)
		  return -1L;

	  return Long.parseLong(String.valueOf(attr.get())) / 1000L;
  }

  @Override
public Issue getIssue(String key) throws IssueTrackerException {
    try {
      key = prepareKey(key);
      if (key == null) {
        return null;
      }

      String urlString = MessageFormat.format(issueUrlTemplate,
          getBaseJiraUrl(), key, getAuthorizedParams());
      URL url = new URL(urlString);
      URLConnection connection = getUrlConnection(url);
      try {
        BufferedReader br = new BufferedReader(
            new InputStreamReader(connection.getInputStream()));
        String line = br.readLine();
        String id = null;
        String summary = null;
        while (line != null) {
          line = decodeString(line);
          Matcher matcherId = patternIssueId.matcher(line);
          if (id == null && matcherId.find()) {
            id = matcherId.group(1);
            continue;
          }

          Matcher matcherSummary = patternSummary.matcher(line);
          if (summary == null && matcherSummary.find()) {
            summary = matcherSummary.group(1);
            continue;
          }

          if (id != null && summary != null) {
            return new JiraIssue(key, id, summary);
          }

          line = br.readLine();
        }
      } finally {
        connection.getInputStream().close();
      }
      return null;
    } catch (FileNotFoundException e) {
      return null;
    } catch (IOException e) {
      throw new IssueTrackerException(e);
    }
  }

  @Override
  public URI getIssueUrl(Task task) throws IssueTrackerException {
    String issueKey = getIssueKey(task);

    if (issueKey == null) {
      throw new IssueTrackerException("Given task \"" + task.getName()
          + "\" is not issue task (i.e. does not has issue key attribute)");
    }

    String uriStr = getBaseJiraUrl() + "/browse/" + issueKey;
    try {
      return new URI(uriStr);
    } catch (URISyntaxException e) {
      throw new IssueTrackerException(
          "Error occured while creating uri: " + uriStr);
    }
  }

  @Override
  public void getFilterIssues(final String filterId, final IssueHandler handler)
      throws IssueTrackerException {
      executorService.execute(() -> {
        try {
			String urlString;

			try {
				Long.parseLong(filterId);

				urlString = MessageFormat.format(filterUrlTemplate,
					getBaseJiraUrl(), filterId, getAuthorizedParams());
			}
			catch (final NumberFormatException e) {
				// https://community.atlassian.com/t5/Jira-questions/Unable-to-fetch-XML-file-when-logged-out-from-JIRA-site/qaq-p/658398
				urlString = getBaseJiraUrl() +
					"/sr/jira.issueviews:searchrequest-xml/temp/SearchRequest.xml?jql=" + java.net.URLEncoder.encode(filterId, "UTF-8");
			}

          URL url = new URL(urlString);
          URLConnection connection = getUrlConnection(url);
          SAXParser saxParser = saxFactory.newSAXParser();

          try (InputStream inputStream = connection.getInputStream()) {
            saxParser.parse(inputStream, new DefaultHandler() {
              StringBuilder stringBuilder = null;
              JiraIssue jiraIssue;

              @Override
              public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                if (handler.stopProcess()) {
                  throw new SAXException("Cancel xml processing");
                }

                switch (qName) {
                  case "summary":
                    stringBuilder = new StringBuilder();
                    break;
                  case "item":
                    jiraIssue = new JiraIssue();
                    break;
                  case "key":
                    jiraIssue.setId(attributes.getValue("id"));
                    stringBuilder = new StringBuilder();
                    break;
                  case "parent":
                    jiraIssue.setSubTask(true);
                    break;
                }
              }

              @Override
              public void characters(char[] ch, int start, int length) throws SAXException {
                if (stringBuilder != null) {
                  stringBuilder.append(new String(ch, start, length));
                }
              }

              @Override
              public void endElement(String uri, String localName, String qName) throws SAXException {
                switch (qName) {
                  case "item":
                    try {
                      handler.handle(jiraIssue);
                    } catch (IssueTrackerException e) {
                      LOG.throwing("", "", e);
                    }
                    jiraIssue = null;
                    break;
                  case "summary":
                    jiraIssue.setSummary(stringBuilder.toString());
                    break;
                  case "key":
                    jiraIssue.setKey(stringBuilder.toString());
                    break;
                }
                stringBuilder = null;
              }
            });
          }
        } catch (Exception e) {
          LOG.throwing("", "", e);
        }
      });
  }

  @Override
public boolean isIssueTask(Task task) {
    return task != null && getIssueKey(task) != null;
  }

  @Override
public boolean isValidKey(String key) {
    String preparedKey = prepareKey(key);
    return preparedKey != null && preparedKey.matches("[a-z,A-Z0-9]+-[0-9]+");
  }

  private void addWorklog(final TimeSlot timeSlot, final String key,
                          final String issueId, Attribute statusAttribute,
                          long duration)
      throws IOException {

	  final StringBuilder sb = new StringBuilder();

	  sb.append(getBaseJiraUrl()).append(getAddWorklogPath(issueId));

	  String requestMethod = "POST";

	  if (version.equals(JIRA_VERSION_6)) {
	    final Attribute worklogId = getAttribute(timeSlot, IssueWorklogIdType.getInstance());

	    if (worklogId != null) {
	    	sb.append('/').append(worklogId.get());
	    	requestMethod = "PUT";
	    }
	  }

	final URL url = new URL(sb.toString());
	final URLConnection connection = getUrlConnection(url);
    if (connection instanceof HttpURLConnection) {
      HttpURLConnection httpConnection = (HttpURLConnection) connection;
      httpConnection.setRequestMethod(requestMethod);
      httpConnection.setDoInput(true);
      httpConnection.setDoOutput(true);
      httpConnection.setUseCaches(false);
      httpConnection.setRequestProperty("Content-Type",
          getContentType());

      // sending data
      try (final OutputStreamWriter writer = new OutputStreamWriter(httpConnection.getOutputStream()))
      {

        String jiraDuration = (duration / 1000 / 60) + "m";
        LOG.finest("addWorkLog => jiraDuration=" + jiraDuration +
        		" started=" + formatDate(timeSlot.getStartDate()) +
        		" comment=" + timeSlot.getDescription() +
        		" toString=" + timeSlot.toString()
        		);
        if (version.equals(JIRA_VERSION_6)) {
        	final JsonObject jsonObject = new JsonObject();

        	jsonObject.addProperty("timeSpentSeconds", duration / 1000L);
        	jsonObject.addProperty("started", formatDate(timeSlot.getStartDate()));
        	jsonObject.addProperty("comment", timeSlot.getDescription());

        	final String json = jsonObject.toString();

        	writer.append(json);
        } else {
          writer.append(getAuthorizedParams()).append(getPair("id", issueId))
              .append(getPair("comment", URLEncoder.encode(timeSlot.getDescription(), "UTF-8")))
              .append(getPair("worklogId", ""))
              .append(getPair("timeLogged", jiraDuration))
              .append(getPair("startDate", URLEncoder.encode(new SimpleDateFormat("dd/MMM/yy KK:mm a")
                      .format(timeSlot.getStartDate()), "UTF-8")))
              .append(getPair("adjustEstimate", "auto"))
              .append(getPair("newEstimate", ""))
              .append(getPair("commentLevel", ""));
        }
      }

      if (version.equals(JIRA_VERSION_6))
      {
          try (final Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
          {
              final JsonObject jsonObj = JsonParser.parseReader(reader).getAsJsonObject();
              final String id = jsonObj.get("id").getAsString();

              getOrCreate(timeSlot, IssueWorklogIdType.getInstance()).set(id);

              LOG.finest("jira result: " + jsonObj.toString());
          }
      }
      else
      {
          try (final BufferedReader br = new BufferedReader(
                  new InputStreamReader(connection.getInputStream())))
          {
              final String line = br.readLine();

              LOG.finest("jira result: " + line);
          }
      }

      if (statusAttribute == null) {
        statusAttribute = new Attribute(issueWorklogStatusType);
        final List<Attribute> list = new ArrayList<Attribute>(timeSlot.getAttributes());
        list.add(statusAttribute);
        timeSlot.setAttributes(list);
      }

      statusAttribute.set(timeSlot.getTime());

      LOG.info("Updated jira worklog with key: " + key);
    }
  }

  private Attribute getOrCreate(final TimeSlot timeslot, final AttributeType attrType)
  {
	  final Collection<Attribute> attrs = timeslot.getAttributes();

	  synchronized(attrs)
	  {
		  if (attrs != null) for (final Attribute attr : attrs)
		  {
			  if (attr.getAttributeType().equals(attrType))
				  return attr;
		  }

		  final Attribute attr = new Attribute(attrType);

		  attrs.add(attr);

		  return attr;
	  }
  }

  private String getAddWorklogPath(String issueId) {
    String path;
    if (version.equals(JIRA_VERSION_3)) {
      path = "/secure/LogWork.jspa";
    }
    else if (version.equals(JIRA_VERSION_310)) {
      path = "/secure/CreateWorklog.jspa";
    }
    else {
      path = "/rest/api/2/issue/" + issueId + "/worklog";
    }
    return path;
  }

  private String getContentType() {
    return version.equals(JIRA_VERSION_6) ? "application/json" : "application/x-www-form-urlencoded";
  }

  private String getAuthorizedParams() {
	  if (version.equals(JIRA_VERSION_6)) {
		return "auth_ignore";
	  } else {
		return "os_username=" + getLogin() + getPair("os_password", getPassword());
	  }
  }

  private URLConnection getUrlConnection(URL url) throws IOException {
	  LOG.finest("Accessing : " + url);
    URLConnection connection = url.openConnection();
    // preparing connection
    if (version.equals(JIRA_VERSION_6)) {
      String basicAuth = "Basic " + new String(new Base64()
          .encode((getLogin() + ":" + getPassword()).getBytes()));
      connection.setRequestProperty("Authorization", basicAuth);
    }
    return connection;
  }

  private String getBaseJiraUrl() {
    String url = this.timeSlotTracker.getConfiguration()
        .getString(Configuration.JIRA_URL, "");

    // truncate symbol / if present
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  private String getIssueKey(Task task) {
    for (Attribute attribute : task.getAttributes()) {
      if (attribute.getAttributeType().equals(issueKeyAttributeType)) {
        return String.valueOf(attribute.get());
      }
    }
    return null;
  }

  private String getLogin() {
    return this.timeSlotTracker.getConfiguration()
        .getString(Configuration.JIRA_LOGIN, "");
  }

  private String getPair(String name, String value) {
    return "&" + name + "=" + value;
  }

  private String getPassword() {
    String password = this.timeSlotTracker.getConfiguration()
        .getString(Configuration.JIRA_PASSWORD, null);
    if (!StringUtils.isBlank(password)) {
      return password;
    }

    if (StringUtils.isBlank(sessionPassword)) {
      sessionPassword = JOptionPane
          .showInputDialog(timeSlotTracker.getRootFrame(), timeSlotTracker
              .getString("issueTracker.credentialsInputDialog.password"));
    }

    return sessionPassword;
  }

  private static final DateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  private static String formatDate(final Date d)
  {
	  synchronized(TIMESTAMP)
	  {
		  return TIMESTAMP.format(d);
	  }
  }

  private void init() {
	  fixFailed();

	  if (Boolean.getBoolean("validate-failed"))
		  validateFailed();

    // updates when timeslot changed
    this.timeSlotTracker.getLayoutManager()
        .addActionListener(new TimeSlotChangedListener() {
          @Override
		public void actionPerformed(Action action) {
            Boolean enabled = timeSlotTracker.getConfiguration()
                .getBoolean(Configuration.JIRA_ENABLED, false);

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

  private void fixFailed()
  {
	  final List<Task> jiraTasks = getAllJiraTasks();

	  for (final Task jiraTask : jiraTasks)
	  {
		  final Collection<TimeSlot> timeslots = jiraTask.getTimeslots();

		  if (timeslots != null) for (final TimeSlot timeslot : timeslots)
		  {
			  final long timeSpentInSeconds = getTimeSpentInSeconds(timeslot);

			  if (timeSpentInSeconds < 0L)
			  {
				  try
				  {
					  add(timeslot);
				  }
				  catch (IssueTrackerException e)
				  {
					  LOG.log(Level.WARNING, "Problem rescheduling Jira update for " +
							  getIssueKey(timeslot.getTask()), e);
				  }
			  }
		  }
	  }
  }

  private void validateFailed()
  {
	  if (!version.equals(JIRA_VERSION_6))
		  return;

	  final List<Task> jiraTasks = getAllJiraTasks();
	  final Map<String, UnsavedWorkLog> unsaved = new HashMap<>();

	  for (final Task jiraTask : jiraTasks)
	  {
		  final List<WorkLog> worklogs = getWorklogs(jiraTask);
		  final List<TimeSlot> missingTimeSlots = getMissingTimeSlots(jiraTask, worklogs);

		  if (!missingTimeSlots.isEmpty())
		  {
			  unsaved.put(getIssueKey(jiraTask), new UnsavedWorkLog(jiraTask, missingTimeSlots));
		  }
	  }

	  LOG.warning(unsaved.size() + " issues are missing timeslots");
  }

  private List<Task> getAllJiraTasks()
  {
	  final List<Task> jiraTasks = new ArrayList<>();

	  findAllJiraTasks(jiraTasks, timeSlotTracker.getDataSource().getRoot());

	  return jiraTasks;
  }

  private void findAllJiraTasks(final List<Task> jiraTasks, final Task task)
  {
	  final String key = getIssueKey(task);

	  if (key != null)
	  {
		  jiraTasks.add(task);
	  }

	  final Collection<Task> children = task.getChildren();

	  if (children != null) for (final Task child : children)
	  {
		  findAllJiraTasks(jiraTasks, child);
	  }
  }

	private List<WorkLog> getWorklogs(final Task jiraTask)
	{
		final List<WorkLog> rtrn = new ArrayList<>();
		final String issueKey = getIssueKey(jiraTask);

		try
		{
			final URL url = new URL(getBaseJiraUrl() + getAddWorklogPath(issueKey));
			final HttpURLConnection httpConnection = (HttpURLConnection) getUrlConnection(url);

			httpConnection.setRequestMethod("GET");
			httpConnection.setDoInput(true);
			httpConnection.setDoOutput(false);
			httpConnection.setUseCaches(false);
			httpConnection.setRequestProperty("Content-Type", "application/json");

			try (final InputStream is = httpConnection.getInputStream();
				 final JsonReader jr = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
			{
				jr.beginObject();

				while (jr.hasNext() && JsonToken.END_OBJECT != jr.peek())
				{
					final String name = jr.nextName();

					if ("worklogs".contentEquals(name))
					{
						jr.beginArray();

						while (jr.hasNext() && JsonToken.END_ARRAY != jr.peek())
						{
							rtrn.add(parseWorkLog(jr));
						}

						jr.endArray();
					}
					else
					{
						jr.skipValue();
					}
				}

				jr.endObject();
			}
		}
		catch (final Exception e)
		{
			LOG.log(Level.WARNING, "Problem while retrieving work logs for Jira issue " + issueKey, e);
		}

		return rtrn;
	}

	private WorkLog parseWorkLog(final JsonReader jr)
			throws IOException, ParseException
	{
		String id = null;
		long timeSpentSeconds = -1L;
		Date started = null;

		jr.beginObject();

		while (jr.hasNext())
		{
			final JsonToken token = jr.peek();

			if (JsonToken.END_OBJECT == token)
				break;

			final String name = jr.nextName();

			switch(name)
			{
			case "id":
				id = jr.nextString();
				break;
			case "timeSpentSeconds":
				timeSpentSeconds = jr.nextLong();
				break;
			case "started":
				synchronized(TIMESTAMP)
				{
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

  private List<TimeSlot> getMissingTimeSlots(final Task jiraTask, final List<WorkLog> worklogs)
  {
	  final Collection<TimeSlot> timeslots = jiraTask.getTimeslots();
	  final List<TimeSlot> missingTimeslots = new ArrayList<>();

	  if (timeslots != null) for (final TimeSlot timeslot : timeslots)
	  {
		  final long timeSpentInSeconds = getTimeSpentInSeconds(timeslot);

		  if (timeSpentInSeconds < 0L)
		  {
			  missingTimeslots.add(timeslot);
		  }
		  else
		  {
			  final WorkLog worklog = findWorkLog(timeslot, worklogs);

			  if (worklog == null)
			  {
				  missingTimeslots.add(timeslot);
			  }
		  }
	  }

	  return missingTimeslots;
  }

  private WorkLog findWorkLog(final TimeSlot timeslot, final List<WorkLog> worklogs)
  {
	  final Date start = timeslot.getStartDate();

	  for (final WorkLog worklog : worklogs)
	  {
		  if (Math.abs(worklog.start.getTime() - start.getTime()) < 5000L)
		  {
			  final long timeSpentSeconds = getTimeSpentInSeconds(timeslot);

			  if (timeSpentSeconds >= 0 && timeSpentSeconds != worklog.timeSpentSeconds)
			  {
				  final String dateStr = formatDate(worklog.start);
				  final String cmp = timeSpentSeconds < worklog.timeSpentSeconds ? "much" : "little";

				  LOG.warning("Work Log time spent does not match for " +
						  getIssueKey(timeslot.getTask()) +
						  " (" + timeSpentSeconds + " vs " + worklog.timeSpentSeconds +
						  " at " + dateStr + " -- Jira has too " + cmp + ")");
			  }

			  return worklog;
		  }
	  }

	  return null;
  }

  private static final class WorkLog
  {
	  private final String id;
	  private final Date start;
	  private final long timeSpentSeconds;

	  private WorkLog(final String id, final Date start, final long duration)
	  {
		  this.id = id;
		  this.start = start;
		  this.timeSpentSeconds = duration;
	  }
  }

  private static final class UnsavedWorkLog
  {
	  private final Task task;
	  private final List<TimeSlot> timeSlots;

	  UnsavedWorkLog(final Task task, final List<TimeSlot> unsavedWorkLogs)
	  {
		  this.task = task;
		  this.timeSlots = unsavedWorkLogs;
	  }

	  List<TimeSlot> geTimeSlots()
	  {
		  return timeSlots;
	  }
  }
}
