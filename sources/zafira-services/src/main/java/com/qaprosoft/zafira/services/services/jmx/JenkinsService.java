package com.qaprosoft.zafira.services.services.jmx;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.*;
import com.qaprosoft.zafira.models.db.Job;
import com.qaprosoft.zafira.models.db.Setting;
import com.qaprosoft.zafira.models.dto.BuildParameterType;
import com.qaprosoft.zafira.services.services.SettingsService;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jmx.export.annotation.*;
import static com.qaprosoft.zafira.models.dto.BuildParameterType.BuildParameterClass.*;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.util.*;

import static com.qaprosoft.zafira.models.db.Setting.Tool.JENKINS;

@ManagedResource(objectName="bean:name=jenkinsService", description="Jenkins init Managed Bean",
		currencyTimeLimit=15, persistPolicy="OnUpdate", persistPeriod=200,
		persistLocation="foo", persistName="bar")
public class JenkinsService implements IJMXService
{
	private static Logger LOGGER = LoggerFactory.getLogger(JenkinsService.class);

	private final String FOLDER_REGEX = ".+job\\/.+\\/job.+";

	private JenkinsServer server;

	@Autowired
	private SettingsService settingsService;

	@Autowired
	private CryptoService cryptoService;

	@Override
	@PostConstruct
	public void init()  {
		String url = null;
		String username = null;
		String passwordOrApiToken = null;

		try {
			List<Setting> jenkinsSettings = settingsService.getSettingsByTool(JENKINS);
			for (Setting setting : jenkinsSettings) {
					if (setting.isEncrypted()) {
						setting.setValue(cryptoService.decrypt(setting.getValue()));
					}
					switch (Setting.SettingType.valueOf(setting.getName())) {
						case JENKINS_URL:
							url = setting.getValue();
							break;
						case JENKINS_USER:
							username = setting.getValue();
							break;
						case JENKINS_API_TOKEN_OR_PASSWORD:
							passwordOrApiToken = setting.getValue();
							break;
						default:
							break;
					}
				}
			init(url, username, passwordOrApiToken);
		} catch(Exception e) {
			LOGGER.error("Setting does not exist", e);
		}
	}

	@ManagedOperation(description="Change Jenkins initialization")
	@ManagedOperationParameters({
			@ManagedOperationParameter(name = "url", description = "Jenkins url"),
			@ManagedOperationParameter(name = "username", description = "Jenkins username"),
			@ManagedOperationParameter(name = "passwordOrApiToken", description = "Jenkins passwordOrApiToken or api token")})
	public void init(String url, String username, String passwordOrApiToken) {
		try
		{
			if (!StringUtils.isEmpty(url) && !StringUtils.isEmpty(username) && !StringUtils.isEmpty(passwordOrApiToken))
			{
				this.server = new JenkinsServer(new URI(url), username, passwordOrApiToken);
			}
		} catch (Exception e)
		{
			LOGGER.error("Unable to initialize Jenkins integration: " + e.getMessage());
		}
	}

	public boolean rerunJob(Job ciJob, Integer buildNumber, boolean rerunFailures)
	{
		boolean success = false;
		try
		{
			JobWithDetails job = getJobWithDetails(ciJob);

			Map<String, String> params = job.getBuildByNumber(buildNumber).details().getParameters();
			if (rerunFailures)
			{
				params.put("rerun_failures", "true");
			}
			QueueReference reference = job.build(params, true);
			success = reference != null && !StringUtils.isEmpty(reference.getQueueItemUrlPart());
		} catch (Exception e)
		{
			LOGGER.error("Unable to rerun Jenkins job:  " + e.getMessage());
		}
		return success;
	}

	public boolean buildJob(Job ciJob, Integer buildNumber, Map<String, String> jobParameters,
			boolean buildWithParameters)
	{
		boolean success = false;
		if (buildWithParameters)
		{
			try
			{
				JobWithDetails job = getJobWithDetails(ciJob);
				QueueReference reference = job.build(jobParameters, true);
				success = reference != null && !StringUtils.isEmpty(reference.getQueueItemUrlPart());
			} catch (Exception e)
			{
				LOGGER.error("Unable to run Jenkins job:  " + e.getMessage());
			}
		} else
		{
			success = rerunJob(ciJob, buildNumber, false);
		}
		return success;
	}

    public List<BuildParameterType> getBuildParameters(Job ciJob, Integer buildNumber)
    {
        List<BuildParameterType> jobParameters = null;
        try
        {
            JobWithDetails job = getJobWithDetails(ciJob);
            jobParameters = getJobParameters(job.getBuildByNumber(buildNumber).details().getActions());
            BuildParameterType buildParameter = new BuildParameterType(HIDDEN, "ci_run_id", UUID.randomUUID().toString());
            jobParameters.add(buildParameter);
        } catch (Exception e)
        {
            LOGGER.error("Unable to get job:  " + e.getMessage());
        }
        return jobParameters;
    }

	public Map<String, String> getBuildParametersMap(Job ciJob, Integer buildNumber)
	{
		Map<String, String> jobParameters = null;
		try
		{
			JobWithDetails job = getJobWithDetails(ciJob);
			jobParameters = job.getBuildByNumber(buildNumber).details().getParameters();
			jobParameters.put("ci_run_id", UUID.randomUUID().toString());
		} catch (Exception e)
		{
			LOGGER.error("Unable to get job:  " + e.getMessage());
		}
		return jobParameters;
	}

	public Map<Integer, String> getBuildConsoleOutputHtml(Job ciJob, Integer buildNumber, Integer stringsCount, Integer fullCount)
	{
		Map<Integer, String> result = new HashMap<>();
		try
		{
			JobWithDetails jobWithDetails = getJobWithDetails(ciJob);
			BuildWithDetails buildWithDetails = jobWithDetails.getBuildByNumber(buildNumber).details();
			buildWithDetails.isBuilding();
			result = getLastLogStringsByCount(buildWithDetails.getConsoleOutputHtml(), stringsCount, fullCount);
			if(! buildWithDetails.isBuilding())
			{
				result.put(-1, buildWithDetails.getDisplayName());
			}
		} catch (IOException e)
		{
			LOGGER.error("Unable to get console output text: " + e.getMessage());
		}
		return result;
	}

	private JobWithDetails getJobWithDetails(Job ciJob) throws IOException
	{
		JobWithDetails job = null;
		if (ciJob.getJobURL().matches(FOLDER_REGEX))
		{
			String folderName = ciJob.getJobURL().split("/job/")[1];
			Optional<FolderJob> folder = server.getFolderJob(server.getJob(folderName));
			job = server.getJob(folder.get(), ciJob.getName());
		} else
		{
			job = server.getJob(ciJob.getName());
		}
		return job;
	}

	private Map<Integer, String> getLastLogStringsByCount(String log, Integer count, Integer fullCount)
	{
		Map<Integer, String> logMap = new HashMap<>();
		int zero = 0;
		String[] strings = log.split("\n");
		count = strings.length < count ? strings.length : count;
		if(fullCount != zero)
		{
			count = strings.length != fullCount ? strings.length - fullCount : zero;
		}
		logMap.put(strings.length, String.join("\n", Arrays.copyOfRange(strings, strings.length - count, strings.length)));
		return logMap;
	}

    private List<BuildParameterType> getJobParameters(List actions) {
        Collection parameters = Collections2.filter(actions, (Predicate<Map<String, Object>>) action -> action.containsKey("parameters"));
        List<BuildParameterType> params = new ArrayList<>();
        if (parameters != null && !parameters.isEmpty()) {
            Iterator iterator = ((List)((Map)parameters.toArray()[0]).get("parameters")).iterator();
            while(iterator.hasNext()) {
                BuildParameterType buildParameter = new BuildParameterType();
                Map<String, Object> param = (Map)iterator.next();
                String name = String.valueOf(param.get("name"));
                String value = String.valueOf(param.get("value"));
                String buildParamClass = String.valueOf(param.get("_class"));
                buildParameter.setName(name);
                buildParameter.setValue(value);
                if (buildParamClass.contains("Hide")){
                    buildParameter.setParameterClass(HIDDEN);
                }
                else if (buildParamClass.contains("String")) {
                    buildParameter.setParameterClass(STRING);
                }
                else if (buildParamClass.contains("Boolean")) {
                    buildParameter.setParameterClass(BOOLEAN);
                }
                if (!name.equals("ci_run_id"))
                params.add(buildParameter);
            }
        }
        return params;
    }


    @Override
	public boolean isConnected()
	{
		return this.server != null && this.server.isRunning();
	}

	@ManagedAttribute(description="Get jenkins server")
	public JenkinsServer getServer() {
		return server;
	}
}