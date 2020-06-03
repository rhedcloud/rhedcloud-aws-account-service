package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.awsaccount.service.provider.ProviderException;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class NotifyAdmins extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "NotifyAdmins";
    private String shortDescription;
    private String longDescription;
    private String urgency;
    private String impact;
    private String businessService;
    private String category;
    private String subcategory;
    private String recordType;
    private String contactType;
    private String cmdbCi;

    @Override
    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        super.init(deprovisioningId, props, aConfig, adp);

        String LOGTAG = createLogTag("init");

        String shortDescription = getProperties().getProperty("shortDescription", null);
        logger.info(LOGTAG + "shortDescription is: " + shortDescription);
        setShortDescription(shortDescription);

        String longDescription = getProperties().getProperty("longDescription", null);
        logger.info(LOGTAG + "longDescription is: " + longDescription);
        setLongDescription(longDescription);

        String urgency = getProperties().getProperty("urgency", null);
        logger.info(LOGTAG + "urgency is: " + urgency);
        setUrgency(urgency);

        String impact = getProperties().getProperty("impact", null);
        logger.info(LOGTAG + "impact is: " + impact);
        setImpact(impact);

        String businessService = getProperties().getProperty("businessService", null);
        logger.info(LOGTAG + "businessService is: " + businessService);
        setBusinessService(businessService);

        String category = getProperties().getProperty("category", null);
        logger.info(LOGTAG + "category is: " + category);
        setCategory(category);

        String subcategory = getProperties().getProperty("subcategory", null);
        logger.info(LOGTAG + "subcategory is: " + subcategory);
        setSubscategory(subcategory);

        String recordType = getProperties().getProperty("recordType", null);
        logger.info(LOGTAG + "recordType is: " + recordType);
        setRecordType(recordType);

        String contactType = getProperties().getProperty("contactType", null);
        logger.info(LOGTAG + "contactType is: " + contactType);
        setContactType(contactType);

        String cmdbCi = getProperties().getProperty("cmdbCi", null);
        logger.info(LOGTAG + "cmdbCi is: " + cmdbCi);
        setCmbdCi(cmdbCi);
    }

    private void setCmbdCi(String cmdbCi) throws StepException {
        if (cmdbCi == null) throw new StepException("cmdbCi cannot be null");
        this.cmdbCi = cmdbCi;
    }

    private void setContactType(String contactType) throws StepException {
        if (contactType == null) throw new StepException("contactType cannot be null");
        this.contactType = contactType;
    }

    private void setRecordType(String recordType) throws StepException {
        if (recordType == null) throw new StepException("recordType cannot be null");
        this.recordType = recordType;
    }

    private void setSubscategory(String subcategory) throws StepException {
        if (subcategory == null) throw new StepException("subcategory cannot be null");
        this.subcategory = subcategory;
    }

    private void setCategory(String category) throws StepException {
        if (category == null) throw new StepException("category cannot be null");
        this.category = category;
    }

    private void setBusinessService(String businessService) throws StepException {
        if (businessService == null) throw new StepException("businessService cannot be null");
        this.businessService = businessService;
    }

    private void setImpact(String impact) {
        this.impact = impact;
    }

    private void setUrgency(String urgency) throws StepException {
        if (urgency == null) throw new StepException("urgency cannot be null");
        this.urgency = urgency;
    }

    private void setLongDescription(String longDescription) throws StepException {
        if (longDescription == null) throw new StepException("longDescription cannot be null");
        this.longDescription = longDescription;
    }

    private void setShortDescription(String shortDescription) throws StepException {
        if (shortDescription == null) throw new StepException("shortDescription cannot be null");
        this.shortDescription = shortDescription;
    }

    @Override
    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("simulate");
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("isAuthorized", "true");

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("run");
        logger.info(LOGTAG + "Begin running the step.");

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        logger.info(LOGTAG + "accountId is: " + accountId);

        List<Property> props = new ArrayList<Property>();
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business login */

        logger.info(LOGTAG + "Preparing to send incident request");
        IncidentRequisition requisition = null;

        try {
            requisition = (IncidentRequisition) getAppConfig().getObjectByType(IncidentRequisition.class.getName());

            requisition.setShortDescription(getShortDescription(accountId));
            requisition.setDescription(getLongDescription(accountId));
            requisition.setUrgency(this.urgency);
            requisition.setImpact(this.impact);
            requisition.setBusinessService(this.businessService);
            requisition.setCategory(this.category);
            requisition.setSubCategory(this.subcategory);
            requisition.setRecordType(this.recordType);
            requisition.setContactType(this.contactType);
            requisition.setCmdbCi(this.cmdbCi);

            Incident incident = getAccountDeprovisioningProvider().generateIncident(requisition);
            String incidentNumber = incident.getNumber();

            logger.info(LOGTAG + "incidentNumber is: " + incidentNumber);
            addResultProperty("incidentNumber", incidentNumber);
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } catch (ProviderException e) {
            e.printStackTrace();
        } catch (EnterpriseFieldException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return props;
    }

    private String getLongDescription(String accountId) {
        return this.longDescription.replace("ACCOUNT_ID", accountId);
    }

    private String getShortDescription(String accountId) {
        return this.shortDescription.replace("ACCOUNT_ID", accountId);
    }

    @Override
    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("fail");
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        update(COMPLETED_STATUS, FAILURE_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("rollback");

        logger.info(LOGTAG + "Rollback called, but this step has nothing to " +
                "roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
    }
}
