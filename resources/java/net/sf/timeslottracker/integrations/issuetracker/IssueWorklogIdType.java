package net.sf.timeslottracker.integrations.issuetracker;

import net.sf.timeslottracker.data.AttributeType;
import net.sf.timeslottracker.data.SimpleTextAttribute;

public class IssueWorklogIdType extends AttributeType
{
	  private static IssueWorklogIdType INSTANCE;
	  static {
	    INSTANCE = new IssueWorklogIdType();
	  }

	  /** do not rename - will be persisted to xml */
	  private static final String NAME = "ISSUE-WORKLOG-ID";

	  private IssueWorklogIdType() {
	    super(new SimpleTextAttribute());

	    setName(NAME);
	    setDescription("Key of worklog");
	    setDefault("");
	    setUsedInTasks(true);
	    setBuiltin(true);
	  }

	  public static IssueWorklogIdType getInstance() {
	    return INSTANCE;
	  }
	}
