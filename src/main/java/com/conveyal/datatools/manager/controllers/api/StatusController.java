package com.conveyal.datatools.manager.controllers.api;

import com.conveyal.datatools.common.status.MonitorableJob;
import com.conveyal.datatools.manager.DataManager;
import com.conveyal.datatools.manager.auth.Auth0UserProfile;
import com.conveyal.datatools.manager.jobs.DeployJob;
import com.conveyal.datatools.manager.models.JsonViews;
import com.conveyal.datatools.manager.utils.json.JsonManager;
import com.google.common.collect.Sets;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static com.conveyal.datatools.common.utils.SparkUtils.logMessageAndHalt;
import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.post;

/**
 * Created by landon on 6/13/16.
 */
public class StatusController {
    public static final Logger LOG = LoggerFactory.getLogger(ProjectController.class);

    public static JsonManager<MonitorableJob.Status> json =
            new JsonManager<>(MonitorableJob.Status.class, JsonViews.UserInterface.class);

    // TODO: Admin API route to return active jobs for all application users.
    private static Set<MonitorableJob> getAllJobsRoute(Request req, Response res) {
        Auth0UserProfile userProfile = req.attribute("user");
        if (!userProfile.canAdministerApplication()) {
            logMessageAndHalt(req, 401, "User not authorized to view all jobs");
        }
        return getAllJobs();
    }

    public static Set<MonitorableJob> getAllJobs() {
        return DataManager.userJobsMap.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    /**
     * API route that returns single job by ID from among the jobs for the currently authenticated user.
     */
    private static MonitorableJob getOneJobRoute(Request req, Response res) {
        String jobId = req.params("jobId");
        Auth0UserProfile userProfile = req.attribute("user");
        // FIXME: refactor underscore in user_id methods
        String userId = userProfile.getUser_id();
        return getJobById(userId, jobId, true);
    }

    /**
     * API route that allows EC2 instances (or other unauthenticated third parties) to remotely update the status of a
     * job, especially a deploy job.
     */
    private static boolean updateJobStatus(Request req, Response res) {
        String jobId = req.params("jobId");
        // Note: this is a public endpoint, so rather than taking the user ID from the auth token, it is supplied as a
        // query parameter.
        String userId = req.queryParams("user");
        if (userId == null) {
            logMessageAndHalt(req, 400, "Must provide valid user ID.");
        }
        MonitorableJob job = getJobById(userId, jobId, true);
        if (job == null) {
            logMessageAndHalt(req, 400, "Must provide valid user ID and job ID.");
        }
        if (job instanceof DeployJob) {
            DeployJob deployJob = (DeployJob) job;
            if (!deployJob.isValidToken(req.queryParams("token"))) {
                logMessageAndHalt(req, HttpStatus.UNAUTHORIZED_401, "Unauthorized to make this request.");
                return false;
            }
            for (String param : req.queryParams()) {
                boolean value = "true".equals(req.queryParams(param));
                if ("graphBuildSuccess".equals(param)) {
                    deployJob.updateGraphBuildStatus(value);
                    return true;
                }
                if ("bundleDownloadSuccess".equals(param)) {
                    deployJob.updateBundleDownloadStatus(value);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * API route that cancels a single job by ID.
     */
    // TODO Add ability to cancel job. This requires some changes to how these jobs are executed. It appears that
    //  only scheduled jobs can be canceled.
//    private static MonitorableJob cancelJob(Request req, Response res) {
//        String jobId = req.params("jobId");
//        Auth0UserProfile userProfile = req.attribute("user");
//        // FIXME: refactor underscore in user_id methods
//        String userId = userProfile.getUser_id();
//        MonitorableJob job = getJobById(userId, jobId, true);
//        return job;
//    }

    /**
     * Gets a job by user ID and job ID.
     * @param clearCompleted if true, remove requested job if it has completed or errored
     */
    public static MonitorableJob getJobById(String userId, String jobId, boolean clearCompleted) {
        // Get jobs set directly from userJobsMap because we may remove an element from it below.
        Set<MonitorableJob> userJobs = DataManager.userJobsMap.get(userId);
        if (userJobs == null) {
            return null;
        }
        for (MonitorableJob job : userJobs) {
            if (job.jobId.equals(jobId)) {
                if (clearCompleted && (job.status.completed || job.status.error)) {
                    // remove job if completed or errored
                    userJobs.remove(job);
                }
                return job;
            }
        }
        // if job is not found (because it doesn't exist or was completed).
        return null;
    }

    /**
     * API route that returns a set of active jobs for the currently authenticated user.
     */
    public static Set<MonitorableJob> getUserJobsRoute(Request req, Response res) {
        Auth0UserProfile userProfile = req.attribute("user");
        // FIXME: refactor underscore in user_id methods
        String userId = userProfile.getUser_id();
        // Get a copy of all existing jobs before we purge the completed ones.
        return getJobsByUserId(userId, true);
    }

    public static Set<MonitorableJob> filterJobsByType (MonitorableJob.JobType ...jobType) {
        return getAllJobs().stream()
                .filter(job -> Arrays.asList(jobType).contains(job.type))
                .collect(Collectors.toSet());
    }

    /**
     * Get set of active jobs by user ID.
     *
     * @param clearCompleted if true, remove all completed and errored jobs for this user.
     */
    private static Set<MonitorableJob> getJobsByUserId(String userId, boolean clearCompleted) {
        Set<MonitorableJob> allJobsForUser = DataManager.userJobsMap.get(userId);
        if (allJobsForUser == null) {
            return Collections.EMPTY_SET;
        }
        if (clearCompleted) {
            // Any active jobs will still have their status updated, so they need to be retrieved again with any status
            // updates. All completed or errored jobs are in their final state and will not be updated any longer, so we
            // remove them once the client has seen them.
            Set<MonitorableJob> jobsStillActive = filterActiveJobs(allJobsForUser);

            DataManager.userJobsMap.put(userId, jobsStillActive);
        }
        return allJobsForUser;
    }

    public static Set<MonitorableJob> filterActiveJobs(Set<MonitorableJob> jobs) {
        // Note: this must be a thread-safe set in case it is placed into the DataManager#userJobsMap.
        Set<MonitorableJob> jobsStillActive = Sets.newConcurrentHashSet();
        jobs.stream()
                .filter(job -> !job.status.completed && !job.status.error)
                .forEach(jobsStillActive::add);
        return jobsStillActive;
    }

    public static void register (String apiPrefix) {

        // These endpoints return all jobs for the current user, all application jobs, or a specific job
        get(apiPrefix + "secure/status/jobs", StatusController::getUserJobsRoute, json::write);
        // FIXME Change endpoint for all jobs (to avoid overlap with jobId param)?
        get(apiPrefix + "secure/status/jobs/all", StatusController::getAllJobsRoute, json::write);
        get(apiPrefix + "secure/status/jobs/:jobId", StatusController::getOneJobRoute, json::write);
        post(apiPrefix + "public/status/jobs/:jobId", StatusController::updateJobStatus, json::write);
        // TODO Add ability to cancel job
//        delete(apiPrefix + "secure/status/jobs/:jobId", StatusController::cancelJob, json::write);
    }
}
