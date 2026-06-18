package io.jenkins.plugins.noconflict;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
//import net.jcip.annotations.GuardedBy;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

// Largely based on hudson.slaves.RetentionStrategy.Demand in Jenkins core and https://github.com/jenkinsci/jenkins/pull/5764
public class OnDemandNoConflicts extends RetentionStrategy<SlaveComputer> {
    private static final Logger logger = Logger.getLogger(Demand.class.getName());

    /**
     * The delay (in minutes) for which the agent must be in demand before trying to launch it.
     */
    private final long inDemandDelay;

    /**
     * The delay (in minutes) for which the agent must be idle before taking it offline.
     */
    private final long idleDelay;

    /**
     * Optional regex (or string in trivial case) with name(s) of node(s)
     * whose being online blocks the current node from starting.
     * To properly take advantage of this, be sure to set low idleDelay
     * timeout values for other workers that no longer have work pending.
     * Note that conflict is evaluated one way (should this node start
     * if inDemandDelay has elapsed?) so all nodes in question should
     * declare each other as conflicting.
     * This allows a Jenkins deployment to co-locate various pre-defined
     * or otherwise provisioned build environments without overwhelming
     * their host - and instead to permit round-robining, one by one.
     */
    private String conflictsWith;

    @DataBoundConstructor
    public OnDemandNoConflicts(long inDemandDelay, long idleDelay) {
        this.inDemandDelay = Math.max(0, inDemandDelay);
        this.idleDelay = Math.max(1, idleDelay);
    }

    /**
     * Getter for property 'inDemandDelay'.
     *
     * @return Value for property 'inDemandDelay'.
     */
    public long getInDemandDelay() {
        return inDemandDelay;
    }

    /**
     * Getter for property 'idleDelay'.
     *
     * @return Value for property 'idleDelay'.
     */
    public long getIdleDelay() {
        return idleDelay;
    }

    /**
     * Getter for property 'conflictsWith'.
     *
     * @return Value for property 'conflictsWith'.
     */
    public String getConflictsWith() {
        return conflictsWith;
    }

    /**
     * Setter for optional property 'conflictsWith'.
     */
    @DataBoundSetter
    public void setConflictsWith(String value) {
        this.conflictsWith = value.trim();
    }

    /**
     * Gets the formatted current time stamp.
     *
     * @return the formatted current time stamp.
     */
    // From https://github.com/jenkinsci/ssh-agents-plugin/blob/main/src/main/java/hudson/plugins/sshslaves/SSHLauncher.java
    @Restricted(NoExternalUse.class)
    public static String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }

    // GuardedBy was deprecated from Jenkins along with JSR-305 annotations
    // (but there was no replacement via spotbugs. So the comment is left for
    // humans rather than machines.
    @Override
    //@GuardedBy("hudson.model.Queue.lock")
    public long check(final SlaveComputer c) {
        if (c.isOffline() && c.isLaunchSupported()) {
            Set<String> hasConflict = new HashSet();
            Pattern conflictsWithPattern = null;
            String cName = c.getName(); // yes we are offline... but better safe than sorry ;)
            if (conflictsWith != null && !conflictsWith.equals("")) {
                try {
                    // Just in case we did get an invalid regex, do not crash
                    conflictsWithPattern = Pattern.compile(conflictsWith);
                } catch (java.util.regex.PatternSyntaxException ep) {
                    logger.log(Level.SEVERE, "Invalid conflictsWith regex ~/{0}/ for computer {1}, ignored: {2}",
                            new Object[]{conflictsWith, cName, ep.getMessage()});
                    conflictsWithPattern = null;
                }
            }

            final HashMap<Computer, Integer> availableComputers = new HashMap<>();
            for (Computer o : Jenkins.get().getComputers()) {
                // For conflictsWith feature, the currently evaluated 'c'
                // agent should not start if certain other 'o' is alive.
                // *Otherwise*, we also care whether the other agent has
                // capacity to process tasks or if 'c' should be added to
                // the pool of workers.
                if (o.isOnline() || o.isConnecting()) {
                    String oName = o.getName();
                    if (conflictsWithPattern != null && !(oName.equals(cName))) {
                        // Check if that other active computer name
                        // blocks this current agent from starting?
                        // We want this checked always, regardless of
                        // other aspects of that other agent's state.
                        Matcher matcher = conflictsWithPattern.matcher(oName);
                        if (matcher.find()) {
                            hasConflict.add(oName);
                        }
                    }
                    if ((!hasConflict.contains(oName)) && o.isPartiallyIdle() && o.isAcceptingTasks()) {
                        // If 'c' has no problem co-existing with 'o',
                        // how available is that other machine now?
                        final int idleExecutors = o.countIdle();
                        if (idleExecutors > 0)
                            availableComputers.put(o, idleExecutors);
                    }
                }
            }

            boolean needComputer = false;
            long demandMilliseconds = 0;
            for (Queue.BuildableItem item : Queue.getInstance().getBuildableItems()) {
                // can any of the currently idle executors take this task?
                // assume the answer is no until we can find such an executor
                boolean needExecutor = true;
                for (Computer o : Collections.unmodifiableSet(availableComputers.keySet())) {
                    Node otherNode = o.getNode();
                    if (otherNode != null && otherNode.canTake(item) == null) {
                        needExecutor = false;
                        final int availableExecutors = availableComputers.remove(o);
                        if (availableExecutors > 1) {
                            availableComputers.put(o, availableExecutors - 1);
                        } else {
                            availableComputers.remove(o);
                        }
                        break;
                    }
                }

                // this 'item' cannot be built by any of the existing idle nodes, but it can be built by 'c'
                Node checkedNode = c.getNode();
                if (needExecutor && checkedNode != null && checkedNode.canTake(item) == null) {
                    demandMilliseconds = System.currentTimeMillis() - item.buildableStartMilliseconds;
                    needComputer = demandMilliseconds > TimeUnit.MINUTES.toMillis(inDemandDelay);
                    break;
                }
            }

            if (needComputer) {
                // we've been in demand for long enough
                String ts = getTimestamp();
                if (!hasConflict.isEmpty()) {
                    /* Would be nice to see this in the agent log UI as well */
                    String msg = MessageFormat.format("{0} Would launch computer [{1}] as it has been in demand for {2}, but it conflicts by regex ~/{3}/ with already active computer(s): {4}",
                            new Object[]{ts, c.getName(), Util.getTimeSpanString(demandMilliseconds), conflictsWith, hasConflict.toString()});
                    c.getListener().getLogger().println(msg);
                    logger.log(Level.WARNING, "{0}", msg);
                } else {
                    String msg = MessageFormat.format("{0} Launching computer [{1}] as it has been in demand for {2}{3}",
                            new Object[]{ts, c.getName(), Util.getTimeSpanString(demandMilliseconds),
                                    (conflictsWithPattern == null ? "" : " and has no conflicting computers matched by regex ~/" + conflictsWith + "/")});
                    logger.log(Level.INFO, "{0}", msg);
                    c.connect(false);
                    c.getListener().getLogger().println(msg);
                }
            }
        } else if (c.isIdle()) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit.MINUTES.toMillis(idleDelay)) {
                // we've been idle for long enough
                String ts = getTimestamp();
                String msg = MessageFormat.format("{0} Disconnecting computer [{1}] as it has been idle for {2}",
                        new Object[]{ts, c.getName(), Util.getTimeSpanString(idleMilliseconds)});
                logger.log(Level.INFO, "{0}", msg);
                c.getListener().getLogger().println(msg);
                c.disconnect(new OfflineCause.IdleOfflineCause());
            } else {
                // no point revisiting until we can be confident we will be idle
                return TimeUnit.MILLISECONDS.toMinutes(TimeUnit.MINUTES.toMillis(idleDelay) - idleMilliseconds);
            }
        }
        return 1;
    }

    @Extension
    @Symbol("demand")
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return Messages.displayName();
        }

        /**
         * Called by {@code RetentionStrategy/Demand/config.jelly} to validate regexes.
         * @return {@link FormValidation#ok} if this item can be populated as specified, otherwise
         * {@link FormValidation#error} with a message explaining the problem.
         */
        public @NonNull
        FormValidation doCheckConflictsWith(@QueryParameter String value) {
            try {
                Pattern pattern = null;
                if (value != null && !value.trim().equals("")) {
                    pattern = Pattern.compile(value);
                    assert pattern != null; // Would have thrown Failure
                }
            } catch (java.util.regex.PatternSyntaxException ep) {
                return FormValidation.error("Invalid regex: " + ep.getMessage());
            } catch (Failure ef) {
                return FormValidation.error("Failed to validate regex: " + ef.getMessage());
            }
            return FormValidation.ok();
        }

    }
}
