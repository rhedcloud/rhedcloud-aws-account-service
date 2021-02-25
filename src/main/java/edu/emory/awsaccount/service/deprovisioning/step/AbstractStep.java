/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountDeprovisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountDeprovisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.DeprovisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.awsaccount.service.provider.ProviderException;
import org.apache.log4j.Category;
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.moa.XmlEnterpriseObjectException;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;

/**
 * An abstract class from which all deprovisioning steps inherit. This class
 * implements common behaviors, such as required instance variable
 * initialization, querying the AWS account service for its state and the
 * ability to update the step in the AWS account service.
 * <p>
 * Step-specific behaviors are implemented in implementations that extend this
 * class and implement the Step interface.
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 11 May 2020
 */
public abstract class AbstractStep {
    private static final String LOGTAG = "[AbstractStep] ";

    protected Category logger = OpenEaiObject.logger;
    private String m_deprovisioningId = null;
    private String m_stepId = null;
    private String m_type = null;
    private String m_description = null;
    private String m_status = null;
    private String m_result = null;
    private List<Property> m_resultProperties = new ArrayList<>();
    private String m_createUser = null;
    private Datetime m_createDatetime = null;
    private String m_lastUpdateUser = null;
    private Datetime m_lastUpdateDatetime = null;
    private boolean m_skipStep = false;
    private boolean m_simulateStep = false;
    private boolean m_failStep = false;
    private AccountDeprovisioning m_ad = null;
    private AccountDeprovisioningProvider m_adp = null;
    private AppConfig m_appConfig = null;

    protected static final String IN_PROGRESS_STATUS = "in progress";
    protected static final String COMPLETED_STATUS = "completed";
    protected static final String PENDING_STATUS = "pending";
    protected static final String ROLLBACK_STATUS = "rolled back";
    protected static final String NO_RESULT = null;
    protected static final String SUCCESS_RESULT = "success";
    protected static final String FAILURE_RESULT = "failure";
    protected static final String RUN_EXEC_TYPE = "executed";
    protected static final String SIMULATED_EXEC_TYPE = "simulated";
    protected static final String SKIPPED_EXEC_TYPE = "skipped";
    protected static final String FAILURE_EXEC_TYPE = "failure";

    protected String m_stepTag = null;
    protected long m_executionStartTime = 0;
    protected long m_executionTime = 0;
    protected long m_executionEndTime = 0;
    protected Properties m_props = null;

    protected final String PROPERTY_VALUE_NOT_AVAILABLE = "not available";

    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {

        String LOGTAG = "[AbstractStep.init] ";
        logger.info(LOGTAG + "Initializing...");

        // Set identification and control properties of the step.
        setAppConfig(aConfig);
        setDeprovisioningId(deprovisioningId);
        setStepId(props.getProperty("stepId"));
        setType(props.getProperty("type"));
        setDescription(props.getProperty("description"));
        setSkipStep(Boolean.parseBoolean(props.getProperty("skipStep", "false")));
        setSimulateStep(Boolean.parseBoolean(props.getProperty("simulateStep", "false")));
        setFailStep(Boolean.parseBoolean(props.getProperty("failStep", "false")));
        setAccountDeprovisioningProvider(adp);
        setProperties(props);

        // Query for the provisioning object.
        queryForAccountDeprovisioningBaseline();

        // If the AccountDeprovisioning object is not null, look for the step.
        DeprovisioningStep step;
        if (getAccountDeprovisioning() != null) {
            step = getDeprovisioningStepById(getStepId());

            // If the provisioning step is present, set the initial values of this
            // step from those of the provisioning step.
            if (step != null) {
                setType(step.getType());
                setDescription(step.getDescription());
                setStatus(step.getStatus());
                setCreateUser(step.getCreateUser());
                setCreateDatetime(step.getCreateDatetime());
                if (step.getLastUpdateUser() != null) {
                    setLastUpdateUser(step.getLastUpdateUser());
                }
                if (step.getLastUpdateDatetime() != null) {
                    setLastUpdateDatetime(step.getLastUpdateDatetime());
                }
            }
            // Otherwise, set initial values.
            else {
                setType(props.getProperty("type"));
                setDescription(props.getProperty("description"));
                setStatus(PENDING_STATUS);
                setCreateUser("AwsAccountService");
                setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
            }
        }
        else {
            String errMsg = "No AccountDeprovisioning object found for DeprovisioningId " + deprovisioningId + ". Can't continue.";
            throw new StepException(errMsg);
        }

        // Set the step tag value.
        String stepTag = "[DeprovisioningId " + getDeprovisioningId() + "][Step-" + getStepId() + "] ";
        setStepTag(stepTag);

        logger.info(LOGTAG + "Initialization complete #######################");
    }

    public List<Property> execute() throws StepException {
        setExecutionStartTime();

        // Update the step to indicate it is in progress.
        update(IN_PROGRESS_STATUS, NO_RESULT);

        String LOGTAG = getStepTag() + "[AbstractStep.execute] ";

        // Determine if the step should be skipped, simulated, or failed.
        // If skipStep is true, log it skip it and return a property indicating
        // that the step was skipped.
        if (getSkipStep()) {
            logger.info(LOGTAG + "skipStep is true, skipping this step.");
            addResultProperty("stepExecutionMethod", "skipped");
            setExecutionTime();
            return getResultProperties();
        }

        // If simulateStep is true, log it and call the simulate method.
        if (getSimulateStep()) {
            logger.info(LOGTAG + "simulateStep is true, simulating this step.");
            List<Property> props = simulate();
            setExecutionTime();
            return props;
        }

        // If failStep is true, log it and call the fail method.
        if (getFailStep()) {
            logger.info(LOGTAG + "failStep is true, failing this step.");
            List<Property> props = fail();
            setExecutionTime();
            return props;
        }

        // Otherwise run the step logic.
        else {
            logger.info(LOGTAG + "Running the step.");
            List<Property> props = run();
            setExecutionTime();
            return props;
        }
    }

    protected abstract List<Property> simulate() throws StepException;

    protected abstract List<Property> run() throws StepException;

    protected abstract List<Property> fail() throws StepException;

    private void setAppConfig(AppConfig appConfig) throws StepException {
        if (appConfig == null) {
            String errMsg = "AppConfig is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
        m_appConfig = appConfig;
    }

    protected AppConfig getAppConfig() {
        return m_appConfig;
    }

    private String getDeprovisioningId() {
        return m_deprovisioningId;
    }

    private void setDeprovisioningId(String deprovisioningId) throws StepException {
        if (deprovisioningId == null) {
            String errMsg = "DeprovisioningId is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_deprovisioningId = deprovisioningId;
    }

    public String getStepId() {
        return m_stepId;
    }

    private void setStepId(String stepId) throws StepException {
        if (stepId == null) {
            String errMsg = "StepId is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
        m_stepId = stepId;
    }

    public String getStepTag() {
        return m_stepTag;
    }

    private void setStepTag(String stepTag) {
        m_stepTag = stepTag;
    }

    private void setSkipStep(boolean skipStep) {
        m_skipStep = skipStep;
    }

    protected boolean getSkipStep() {
        return m_skipStep;
    }

    private void setSimulateStep(boolean simulateStep) {
        m_simulateStep = simulateStep;
    }

    protected boolean getSimulateStep() {
        return m_simulateStep;
    }

    private void setFailStep(boolean failStep) {
        m_failStep = failStep;
    }

    private boolean getFailStep() {
        return m_failStep;
    }

    private void setAccountDeprovisioningProvider(AccountDeprovisioningProvider adp) {
        m_adp = adp;
    }

    protected AccountDeprovisioningProvider getAccountDeprovisioningProvider() {
        return m_adp;
    }

    private void setAccountDeprovisioning(AccountDeprovisioning ad) {
        m_ad = ad;
    }

    protected AccountDeprovisioning getAccountDeprovisioning() {
        return m_ad;
    }

    public String getType() {
        return m_type;
    }

    private void setType(String type) throws StepException {
        if (type == null) {
            String errMsg = "Type is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_type = type;
    }

    public String getDescription() {
        return m_description;
    }

    private void setDescription(String description) throws StepException {
        if (description == null) {
            String errMsg = "Description is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_description = description;
    }

    public String getStatus() {
        return m_status;
    }

    private void setStatus(String status) throws StepException {
        if (status == null) {
            String errMsg = "Status is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_status = status;
    }

    public Properties getProperties() {
        return m_props;
    }

    private void setProperties(Properties props) {
        m_props = props;
    }

    public String getResult() {
        return m_result;
    }

    private void setResult(String result) {
        m_result = result;
    }

    private String getCreateUser() {
        return m_createUser;
    }

    private void setCreateUser(String createUser) throws StepException {
        if (createUser == null) {
            String errMsg = "CreateUser is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_createUser = createUser;
    }

    private Datetime getCreateDatetime() {
        return m_createDatetime;
    }

    private void setCreateDatetime(Datetime createDatetime) throws StepException {
        if (createDatetime == null) {
            String errMsg = "CreateDatetime is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_createDatetime = createDatetime;
    }

    private String getLastUpdateUser() {
        return m_lastUpdateUser;
    }

    private void setLastUpdateUser(String lastUpdateUser) throws StepException {
        if (lastUpdateUser == null) {
            String errMsg = "LastUpdateUser is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_lastUpdateUser = lastUpdateUser;
    }

    private Datetime getLastUpdateDatetime() {
        return m_lastUpdateDatetime;
    }

    private void setLastUpdateDatetime(Datetime lastUpdateDatetime) throws StepException {
        if (lastUpdateDatetime == null) {
            String errMsg = "LastUpdateDatetime is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_lastUpdateDatetime = lastUpdateDatetime;
    }

    protected DeprovisioningStep getDeprovisioningStepById(String stepId) {
        @SuppressWarnings("unchecked")
        List<DeprovisioningStep> steps = getAccountDeprovisioning().getDeprovisioningStep();
        for (DeprovisioningStep step : steps) {
            if (step.getStepId().equals(stepId)) {
                return step;
            }
        }
        return null;
    }

    protected DeprovisioningStep getDeprovisioningStepByType(String stepType) {
        @SuppressWarnings("unchecked")
        List<DeprovisioningStep> steps = getAccountDeprovisioning().getDeprovisioningStep();
        for (DeprovisioningStep step : steps) {
            if (step.getType().equalsIgnoreCase(stepType)) {
                return step;
            }
        }
        return null;
    }

    protected void setResultProperties(List<Property> resultProps) {
        m_resultProperties = resultProps;
    }

    public void addResultProperty(String key, String value) throws StepException {
        String LOGTAG = getStepTag() + "[AbstractStep.addResultProperty] ";
        logger.debug(LOGTAG + "Adding result property " + key + ": " + value);

        if (getResultProperties() == null) {
            setResultProperties(new ArrayList<>());
        }

        Property newProp = m_ad.newDeprovisioningStep().newProperty();
        try {
            newProp.setKey(key);
            newProp.setValue(value);
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the field values of a property object. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // If the list already contains a Property with this key value, update its value.
        boolean replacedValue = false;
        List<Property> properties = getResultProperties();
        for (Property oldProp : properties) {
            if (oldProp.getKey().equalsIgnoreCase(key)) {
                try {
                    oldProp.setValue(value);
                    logger.debug(LOGTAG + "Found an existing property with key " + key + ". Replaced its value with: " + value);
                }
                catch (EnterpriseFieldException efe) {
                    String errMsg = "An error occurred setting the field values of a property object. The exception is: " + efe.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, efe);
                }
                replacedValue = true;
            }
        }
        // Otherwise, add the new property.
        if (replacedValue == false) {
            properties.add(newProp);
            logger.debug(LOGTAG + "No existing property with key " + key + ". Added property with value: " + value);
        }
    }

    public List<Property> getResultProperties() {
        return m_resultProperties;
    }

    protected String getResultProperty(String key) {
        for (Property prop : m_resultProperties) {
            if (prop.getKey().equalsIgnoreCase(key)) {
                return prop.getValue();
            }
        }
        return null;
    }

    protected String getResultProperty(DeprovisioningStep step, String key) {
        @SuppressWarnings("unchecked")
        List<Property> resultProperties = step.getProperty();
        for (Property prop : resultProperties) {
            if (prop.getKey().equalsIgnoreCase(key)) {
                return prop.getValue();
            }
        }
        return null;
    }

    protected void setExecutionStartTime() throws StepException {
        String LOGTAG = getStepTag() + "[AbstractStep.setExecutionStartTime] ";

        m_executionStartTime = System.currentTimeMillis();

        addResultProperty("startTime", Long.toString(getExecutionStartTime()));

        java.util.Date date = new java.util.Date(getExecutionStartTime());
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
        String formattedDate = format.format(date);

        addResultProperty("startTimeFormatted", formattedDate);

        logger.info(LOGTAG + "Set step startTime to " + getExecutionStartTime() + " or: " + formattedDate);
    }

    protected long getExecutionStartTime() {
        return m_executionStartTime;
    }

    protected void setExecutionTime() throws StepException {
        long currentTime = System.currentTimeMillis();
        m_executionTime = currentTime - getExecutionStartTime();
        logger.info(LOGTAG + "Setting execution time: " + m_executionTime + " = " + currentTime + " - " + m_executionStartTime);

        addResultProperty("executionTime", Long.toString(getExecutionTime()));

        logger.info(LOGTAG + "Set step executionTime to " + m_executionTime);
    }

    protected long getExecutionTime() {
        logger.info(LOGTAG + "Returning execution time: " + m_executionTime);
        return m_executionTime;
    }

    protected void setEndTime() throws StepException {
        m_executionEndTime = System.currentTimeMillis();

        addResultProperty("executionEndTime", Long.toString(m_executionEndTime));

        java.util.Date date = new java.util.Date(m_executionEndTime);
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
        String formattedDate = format.format(date);

        addResultProperty("executionEndTimeFormatted", formattedDate);

        logger.info(LOGTAG + "Set step executionEndTime to " + getExecutionEndTime() + "or: " + formattedDate);
    }

    protected long getExecutionEndTime() {
        return m_executionEndTime;
    }

    public void update(String status, String result) throws StepException {
        String LOGTAG = getStepTag() + "[AbstractStep.update] ";
        logger.info(LOGTAG + "Updating step with status " + status + " and result " + result);

        // Update the baseline state of the VPCP
        queryForAccountDeprovisioningBaseline();

        // If the status is changing from in progress to anything else,
        // set the executionEndTime.
        if (getStatus().equalsIgnoreCase(IN_PROGRESS_STATUS) == true &&
                status.equalsIgnoreCase(IN_PROGRESS_STATUS) == false) {
            setExecutionTime();
            setEndTime();
        }

        // Update the fields of this step.
        setStatus(status);
        setResult(result);

        // Get the corresponding provisioning step.
        DeprovisioningStep dStep = getDeprovisioningStepById(getStepId());

        // Update the step values.
        try {
            dStep.setStatus(getStatus());
            dStep.setStepResult(getResult());
            dStep.setProperty(getResultProperties());
            dStep.setLastUpdateUser("AwsAccountService");
            dStep.setLastUpdateDatetime(new Datetime("LastUpdate", System.currentTimeMillis()));
            dStep.setActualTime(Long.toString(getExecutionTime()));
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the field values of the ProvisioningStep. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Perform the step update.
        try {
            long updateStartTime = System.currentTimeMillis();
            logger.info(LOGTAG + "Updating the AccountDeprovisioning with new step information...");
            getAccountDeprovisioningProvider().update(getAccountDeprovisioning());
            long time = System.currentTimeMillis() - updateStartTime;
            logger.info(LOGTAG = "Updated AccountDeprovisioning with new step state in " + time + " ms.");
        }
        catch (ProviderException pe) {
            String errMsg = "An error occurred updating the AccountDeprovisioning object with an updated DeprovisioningStep. The exception is: " + pe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, pe);
        }
    }

    protected void rollback() throws StepException {
        String LOGTAG = "[AbstractStep.rollback] ";
        logger.info(LOGTAG + "Initializing common rollback logic...");
        logger.info(LOGTAG + "Querying for AccountDeprovisioning baseline...");
        queryForAccountDeprovisioningBaseline();
    }

    private void queryForAccountDeprovisioningBaseline() throws StepException {
        // Query for the AccountDeprovisioning object in the AWS Account Service.
        // Get a configured query spec from AppConfig
        AccountDeprovisioningQuerySpecification querySpec = new AccountDeprovisioningQuerySpecification();
        try {
            querySpec = (AccountDeprovisioningQuerySpecification) getAppConfig().getObjectByType(querySpec.getClass().getName());
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }

        // Set the values of the query spec.
        try {
            querySpec.setDeprovisioningId(getDeprovisioningId());
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the AccountDeprovisioning query spec. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Log the state of the query spec.
        try {
            logger.info(LOGTAG + "Query spec is: " + querySpec.toXmlString());
        }
        catch (XmlEnterpriseObjectException xeoe) {
            String errMsg = "An error occurred serializing the query spec to XML. The exception is: " + xeoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, xeoe);
        }

        try {
            List<AccountDeprovisioning> results = getAccountDeprovisioningProvider().query(querySpec);
            setAccountDeprovisioning(results.get(0));
        }
        catch (ProviderException pe) {
            String errMsg = "An error occurred querying for the  current state of a AccountDeprovisioning object. The exception is: " + pe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, pe);
        }
    }

    protected String getStepPropertyValue(String stepType, String key) throws StepException {
        String LOGTAG = getStepTag() + "[AbstractStep.getStepPropertyValue] ";

        // Get the property value with the named step and key.
        logger.info(LOGTAG + "Getting " + key + " property from preceding step " + stepType);
        DeprovisioningStep step = getDeprovisioningStepByType(stepType);
        if (step != null) {
            String value = getResultProperty(step, key);
            if (value == null || value.equals(""))
                value = PROPERTY_VALUE_NOT_AVAILABLE;
            addResultProperty(key, value);
            logger.info(LOGTAG + "Property " + key + " from preceding step " + stepType + " is: " + value);

            return value;
        }
        else {
            String errMsg = "Step " + stepType + " not found. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
    }
}
