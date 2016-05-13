package jenkins.plugins.slack;

import hudson.EnvVars;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.triggers.SCMTrigger;
import hudson.util.LogTaskListener;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

    private static final Logger logger = Logger.getLogger(SlackListener.class.getName());
    public static final String GHPRB_SOURCE_BRANCH = "ghprbSourceBranch";
    public static final String GHPRB_PULL_ID = "ghprbPullId";
    public static final String GHPRB_PULL_LINK = "ghprbPullLink";
    public static final String GHPRB_ACTUAL_COMMIT_AUTHOR = "ghprbActualCommitAuthor";

    SlackNotifier notifier;
    BuildListener listener;

    public ActiveNotifier(SlackNotifier notifier, BuildListener listener) {
        super();
        this.notifier = notifier;
        this.listener = listener;
    }

    private SlackService getSlack(AbstractBuild r) {
        return notifier.newSlackService(r, listener);
    }

    public void deleted(AbstractBuild r) {
    }

    public void started(AbstractBuild build) {

        AbstractProject<?, ?> project = build.getProject();

        CauseAction causeAction = build.getAction(CauseAction.class);

        if (causeAction != null) {
            Cause scmCause = causeAction.findCause(SCMTrigger.SCMTriggerCause.class);
            if (scmCause == null) {
                MessageBuilder message = new MessageBuilder(notifier, build);
                message.append(causeAction.getShortDescription());
                notifyStart(build, message.appendOpenLink().toString());
                // Cause was found, exit early to prevent double-message
                return;
            }
        }

        String changes = getChanges(build, notifier.includeCustomMessage());
        if (changes != null) {
            notifyStart(build, changes);
        } else {
            notifyStart(build, getStartedMessage(build, false, notifier.includeCustomMessage()));
        }
    }

    private void notifyStart(AbstractBuild build, String message) {
        AbstractProject<?, ?> project = build.getProject();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild().getPreviousCompletedBuild();
        if (previousBuild == null) {
            getSlack(build).publish(message, "good");
        } else {
            getSlack(build).publish(message, getBuildColor(previousBuild));
        }
    }

    public void finalized(AbstractBuild r) {
    }

    public void completed(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        Result result = r.getResult();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild();
        do {
            previousBuild = previousBuild.getPreviousCompletedBuild();
        } while (previousBuild != null && previousBuild.getResult() == Result.ABORTED);
        Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;


        ResultMessageType messageType = getMessageType(result, previousResult, notifier);
        if (messageType != ResultMessageType.NO_MESSAGE) {
            String message = getBuildStatusMessage(
                    messageType, r, notifier.includeTestSummary(), notifier.includeCustomMessage());

            // Send the message
            String buildColor = getBuildColor(r);
            getSlack(r).publish(message, buildColor);
            if (notifier.getCommitInfoChoice().showAnything()) {
                getSlack(r).publish(getCommitList(r), buildColor);
            }
        }
    }

    ResultMessageType getMessageType(Result result, Result previousResult, SlackNotifier notifier) {
        if (result == Result.ABORTED && notifier.getNotifyAborted()) {
            return ResultMessageType.ABORTED;
        }

        // Single failure
        if (result == Result.FAILURE && previousResult != Result.FAILURE && notifier.getNotifyFailure()) {
            return ResultMessageType.FAILURE;
        }

        if (result == Result.FAILURE && notifier.getNotifyRepeatedFailure()) {
            return ResultMessageType.REPEATED_FAILURE;
        }

        if (result == Result.NOT_BUILT && notifier.getNotifyNotBuilt()) {
            return ResultMessageType.NOT_BUILT;
        }

        if (result == Result.SUCCESS
                && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)
                && notifier.getNotifyBackToNormal()) {
            return ResultMessageType.BACK_TO_NORMAL;
        }

        if (result == Result.SUCCESS && notifier.getNotifySuccess()) {
            return ResultMessageType.SUCCESS;
        }

        if (result == Result.UNSTABLE && notifier.getNotifyUnstable()) {
            return ResultMessageType.UNSTABLE;
        }

        return ResultMessageType.NO_MESSAGE;
    }

    enum ResultMessageType {
        ABORTED,
        SUCCESS,
        BACK_TO_NORMAL,
        FAILURE,
        REPEATED_FAILURE,
        NOT_BUILT,
        UNSTABLE,
        NO_MESSAGE
    }

    String getChanges(AbstractBuild r, boolean includeCustomMessage) {
        if (!r.hasChangeSetComputed()) {
            logger.info("No change set computed...");
            return null;
        }
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
            files.addAll(entry.getAffectedFiles());
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            return null;
        }
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add(entry.getAuthor().getDisplayName());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Started by changes from ");
        message.append(StringUtils.join(authors, ", "));
        message.append(" (");
        message.append(files.size());
        message.append(" file(s) changed)");
        message.appendOpenLink();
        if (includeCustomMessage) {
            message.appendCustomMessage();
        }
        return message.toString();
    }

    String getCommitList(AbstractBuild r) {
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            Cause.UpstreamCause c = (Cause.UpstreamCause) r.getCause(Cause.UpstreamCause.class);
            if (c == null) {
                return "No Changes.";
            }
            String upProjectName = c.getUpstreamProject();
            int buildNumber = c.getUpstreamBuild();
            AbstractProject project = Hudson.getInstance().getItemByFullName(upProjectName, AbstractProject.class);
            AbstractBuild upBuild = (AbstractBuild) project.getBuildByNumber(buildNumber);
            return getCommitList(upBuild);
        }
        Set<String> commits = new HashSet<String>();
        for (Entry entry : entries) {
            StringBuffer commit = new StringBuffer();
            CommitInfoChoice commitInfoChoice = notifier.getCommitInfoChoice();
            if (commitInfoChoice.showTitle()) {
                commit.append(entry.getMsg());
            }
            if (commitInfoChoice.showAuthor()) {
                commit.append(" [").append(entry.getAuthor().getDisplayName()).append("]");
            }
            commits.add(commit.toString());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Changes:\n- ");
        message.append(StringUtils.join(commits, "\n- "));
        return message.toString();
    }

    static String getBuildColor(AbstractBuild r) {
        Result result = r.getResult();
        if (result == Result.SUCCESS) {
            return "good";
        } else if (result == Result.FAILURE) {
            return "danger";
        } else {
            return "warning";
        }
    }

    String getStartedMessage(AbstractBuild r, boolean includeTestSummary, boolean includeCustomMessage) {
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.appendStatusMessage();
        message.appendDuration();
        message.appendOpenLink();
        if (includeTestSummary) {
            message.appendTestSummary();
        }
        if (includeCustomMessage) {
            message.appendCustomMessage();
        }
        return message.toString();
    }

    String getBuildStatusMessage(ResultMessageType type, AbstractBuild r,
                                 boolean includeTestSummary, boolean includeCustomMessage) {

        StatusMessageBuilder message = new StatusMessageBuilder(notifier, r);
        // Add Build #XXX
        message.append("Build").append(' ').appendBuildLink();

        Map<String, String> variables = r.getBuildVariables();
        if (variables.containsKey(GHPRB_SOURCE_BRANCH)) {
            message.append(" of ").append(variables.get(GHPRB_SOURCE_BRANCH));
        } else {
            message.append(" of ").append(r.getProject().getFullDisplayName());


            List causes = r.getCauses();
            if (causes.size() > 0) {
                message.append(" triggered by ");
                boolean needsComma = false;
                for (Object cause : causes) {
                    String causeString = cause.toString();
                    if (causeString != null && causeString.length() > 0) {
                        if (needsComma) {
                            message.append(", ");
                        }

                        message.append(causeString);
                        needsComma = true;
                    }
                }
            }
        }

        if (variables.containsKey(GHPRB_PULL_ID)) {
            message.append(" in PR ");
            message.appendLink(variables.get(GHPRB_PULL_LINK), "#" + variables.get(GHPRB_PULL_ID));
            message.append(" by ");
            message.append(variables.get(GHPRB_ACTUAL_COMMIT_AUTHOR));
        }

        message.append(" ");
        switch (type) {
            case SUCCESS:
            case BACK_TO_NORMAL:
                message.append("passed");
                break;
            case FAILURE:
            case REPEATED_FAILURE:
                message.append("failed");
                break;
            case ABORTED:
                message.append("aborted");
                break;
            case NOT_BUILT:
                message.append("failed to build");
                break;
            case UNSTABLE:
                message.append("is unstable");
                break;
            default:
                message.append("something weird happened");
                break;
        }

        message.append(" after ");
        message.append(r.getDurationString());

        if (includeTestSummary) {
            message.appendTestSummary();
        }
        if (includeCustomMessage) {
            message.appendCustomMessage();
        }
        return message.toString();
    }


    public static class StatusMessageBuilder {
        private StringBuffer message;
        private SlackNotifier notifier;
        private AbstractBuild build;

        public StatusMessageBuilder(SlackNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
        }

        public StatusMessageBuilder appendLink(String link, String text) {
            message.append('<')
                    .append(link)
                    .append('|')
                    .append(text)
                    .append('>');
            return this;
        }

        public StatusMessageBuilder append(String string) {
            message.append(this.escape(string));
            return this;
        }

        public StatusMessageBuilder append(Object string) {
            message.append(this.escape(string.toString()));
            return this;
        }

        public StatusMessageBuilder appendBuildLink() {
            return appendLink(getBuildUrl(), "#" + build.getId());
        }

        private String getBuildUrl() {
            return notifier.getBuildServerUrl() + build.getUrl();
        }

        public StatusMessageBuilder appendTestSummary() {
            AbstractTestResultAction<?> action = this.build
                    .getAction(AbstractTestResultAction.class);
            if (action != null) {
                int total = action.getTotalCount();
                int failed = action.getFailCount();
                int skipped = action.getSkipCount();
                message.append("\nTest Status:\n");
                message.append("\tPassed: " + (total - failed - skipped));
                message.append(", Failed: " + failed);
                message.append(", Skipped: " + skipped);
            } else {
                message.append("\nNo Tests found.");
            }
            return this;
        }

        public StatusMessageBuilder appendCustomMessage() {
            String customMessage = notifier.getCustomMessage();
            EnvVars envVars = new EnvVars();
            try {
                envVars = build.getEnvironment(new LogTaskListener(logger, INFO));
            } catch (IOException e) {
                logger.log(SEVERE, e.getMessage(), e);
            } catch (InterruptedException e) {
                logger.log(SEVERE, e.getMessage(), e);
            }
            message.append("\n");
            message.append(envVars.expand(customMessage));
            return this;
        }

        public String escape(String string) {
            string = string.replace("&", "&amp;");
            string = string.replace("<", "&lt;");
            string = string.replace(">", "&gt;");

            return string;
        }

        public String toString() {
            return message.toString();
        }
    }

    public static class MessageBuilder {

        private static final String STARTING_STATUS_MESSAGE = "Starting...",
                BACK_TO_NORMAL_STATUS_MESSAGE = "Back to normal",
                STILL_FAILING_STATUS_MESSAGE = "Still Failing",
                SUCCESS_STATUS_MESSAGE = "Success",
                FAILURE_STATUS_MESSAGE = "Failure",
                ABORTED_STATUS_MESSAGE = "Aborted",
                NOT_BUILT_STATUS_MESSAGE = "Not built",
                UNSTABLE_STATUS_MESSAGE = "Unstable",
                UNKNOWN_STATUS_MESSAGE = "Unknown";

        private StringBuffer message;
        private SlackNotifier notifier;
        private AbstractBuild build;

        public MessageBuilder(SlackNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
            startMessage();
        }

        public MessageBuilder appendStatusMessage() {
            message.append(this.escape(getStatusMessage(build)));
            return this;
        }

        static String getStatusMessage(AbstractBuild r) {
            if (r.isBuilding()) {
                return STARTING_STATUS_MESSAGE;
            }
            Result result = r.getResult();
            Result previousResult;
            Run previousBuild = r.getProject().getLastBuild().getPreviousBuild();
            Run previousSuccessfulBuild = r.getPreviousSuccessfulBuild();
            boolean buildHasSucceededBefore = previousSuccessfulBuild != null;
            
            /*
             * If the last build was aborted, go back to find the last non-aborted build.
             * This is so that aborted builds do not affect build transitions.
             * I.e. if build 1 was failure, build 2 was aborted and build 3 was a success the transition
             * should be failure -> success (and therefore back to normal) not aborted -> success. 
             */
            Run lastNonAbortedBuild = previousBuild;
            while (lastNonAbortedBuild != null && lastNonAbortedBuild.getResult() == Result.ABORTED) {
                lastNonAbortedBuild = lastNonAbortedBuild.getPreviousBuild();
            }
            
            
            /* If all previous builds have been aborted, then use 
             * SUCCESS as a default status so an aborted message is sent
             */
            if (lastNonAbortedBuild == null) {
                previousResult = Result.SUCCESS;
            } else {
                previousResult = lastNonAbortedBuild.getResult();
            }
            
            /* Back to normal should only be shown if the build has actually succeeded at some point.
             * Also, if a build was previously unstable and has now succeeded the status should be 
             * "Back to normal"
             */
            if (result == Result.SUCCESS
                    && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)
                    && buildHasSucceededBefore) {
                return BACK_TO_NORMAL_STATUS_MESSAGE;
            }
            if (result == Result.FAILURE && previousResult == Result.FAILURE) {
                return STILL_FAILING_STATUS_MESSAGE;
            }
            if (result == Result.SUCCESS) {
                return SUCCESS_STATUS_MESSAGE;
            }
            if (result == Result.FAILURE) {
                return FAILURE_STATUS_MESSAGE;
            }
            if (result == Result.ABORTED) {
                return ABORTED_STATUS_MESSAGE;
            }
            if (result == Result.NOT_BUILT) {
                return NOT_BUILT_STATUS_MESSAGE;
            }
            if (result == Result.UNSTABLE) {
                return UNSTABLE_STATUS_MESSAGE;
            }
            return UNKNOWN_STATUS_MESSAGE;
        }

        public MessageBuilder append(String string) {
            message.append(this.escape(string));
            return this;
        }

        public MessageBuilder append(Object string) {
            message.append(this.escape(string.toString()));
            return this;
        }

        private MessageBuilder startMessage() {
            message.append(this.escape(build.getProject().getFullDisplayName()));
            message.append(" - ");
            message.append(this.escape(build.getDisplayName()));
            message.append(" ");
            return this;
        }

        public MessageBuilder appendOpenLink() {
            String url = notifier.getBuildServerUrl() + build.getUrl();
            message.append(" (<").append(url).append("|Open>)");
            return this;
        }

        public MessageBuilder appendDuration() {
            message.append(" after ");
            String durationString;
            if (message.toString().contains(BACK_TO_NORMAL_STATUS_MESSAGE)) {
                durationString = createBackToNormalDurationString();
            } else {
                durationString = build.getDurationString();
            }
            message.append(durationString);
            return this;
        }

        public MessageBuilder appendTestSummary() {
            AbstractTestResultAction<?> action = this.build
                    .getAction(AbstractTestResultAction.class);
            if (action != null) {
                int total = action.getTotalCount();
                int failed = action.getFailCount();
                int skipped = action.getSkipCount();
                message.append("\nTest Status:\n");
                message.append("\tPassed: " + (total - failed - skipped));
                message.append(", Failed: " + failed);
                message.append(", Skipped: " + skipped);
            } else {
                message.append("\nNo Tests found.");
            }
            return this;
        }

        public MessageBuilder appendCustomMessage() {
            String customMessage = notifier.getCustomMessage();
            EnvVars envVars = new EnvVars();
            try {
                envVars = build.getEnvironment(new LogTaskListener(logger, INFO));
            } catch (IOException e) {
                logger.log(SEVERE, e.getMessage(), e);
            } catch (InterruptedException e) {
                logger.log(SEVERE, e.getMessage(), e);
            }
            message.append("\n");
            message.append(envVars.expand(customMessage));
            return this;
        }

        private String createBackToNormalDurationString() {
            Run previousSuccessfulBuild = build.getPreviousSuccessfulBuild();
            long previousSuccessStartTime = previousSuccessfulBuild.getStartTimeInMillis();
            long previousSuccessDuration = previousSuccessfulBuild.getDuration();
            long previousSuccessEndTime = previousSuccessStartTime + previousSuccessDuration;
            long buildStartTime = build.getStartTimeInMillis();
            long buildDuration = build.getDuration();
            long buildEndTime = buildStartTime + buildDuration;
            long backToNormalDuration = buildEndTime - previousSuccessEndTime;
            return Util.getTimeSpanString(backToNormalDuration);
        }

        public String escape(String string) {
            string = string.replace("&", "&amp;");
            string = string.replace("<", "&lt;");
            string = string.replace(">", "&gt;");

            return string;
        }

        public String toString() {
            return message.toString();
        }
    }
}
