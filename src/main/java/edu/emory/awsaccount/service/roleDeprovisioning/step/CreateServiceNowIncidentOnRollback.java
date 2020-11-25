/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningStep;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;


/**
 * Create a ServiceNow incident but only on rollback because an error occurred during provisioning.
 */
public class CreateServiceNowIncidentOnRollback extends AbstractStep implements Step {
    private ProducerPool serviceNowServiceProducerPool = null;
    private boolean createIncidentOnFailure;
    private final Properties incidentProperties = new Properties();

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[CreateServiceNowIncidentOnRollback.init] ";

        // This step needs to send messages to the LDS Service to provision the group for the account.
        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("ServiceNowServiceProducerPool");
            setServiceNowServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Getting custom step properties...");

        setCreateIncidentOnFailure(getMandatoryBooleanProperty(LOGTAG, "createIncidentOnFailure", false));

        final String prefix = "IncidentProperty:";
        props.forEach((k, v) -> {
            if (k.toString().startsWith(prefix)) {
                incidentProperties.setProperty(k.toString().substring(prefix.length()), v.toString());
            }
        });

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateServiceNowIncidentOnRollback.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateServiceNowIncidentOnRollback.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateServiceNowIncidentOnRollback.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_FAILURE);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_FAILURE);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    public void rollback() throws StepException {
        super.rollback();
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateServiceNowIncidentOnRollback.rollback] ";

        if (!isCreateIncidentOnFailure()) {
            logger.info(LOGTAG + "Not creating ServiceNow Incident");
            update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);
            return;
        }

        // it could happen that there is no failedStep
        // for example, if an exception is thrown during instantiation of the step
        RoleDeprovisioningStep failedStep = getFailedProvisioningStep();

        // Get a configured Incident object and query spec from AppConfig.
        IncidentRequisition incidentRequisition;
        Incident incident;
        try {
            incident = (Incident) getAppConfig().getObjectByType(Incident.class.getName());
            incidentRequisition = (IncidentRequisition) getAppConfig().getObjectByType(IncidentRequisition.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // reflectively populate the requisition from the incident properties
        Class<? extends IncidentRequisition> incidentRequisitionClass = incidentRequisition.getClass();
        for (Object key : incidentProperties.keySet()) {
            try {
                Method setter = incidentRequisitionClass.getMethod("set" + key, String.class);
                setter.invoke(incidentRequisition, incidentProperties.getProperty((String) key));
            }
            catch (NoSuchMethodException e) {
                String errMsg = "[NoSuchMethodException] An error occurred populating the IncidentRequisition object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }
            catch (SecurityException e) {
                String errMsg = "[SecurityException] An error occurred populating the IncidentRequisition object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }
            catch (IllegalAccessException e) {
                String errMsg = "[IllegalAccessException] An error occurred populating the IncidentRequisition object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }
            catch (IllegalArgumentException e) {
                String errMsg = "[IllegalArgumentException] An error occurred populating the IncidentRequisition object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }
            catch (InvocationTargetException e) {
                String errMsg = "[InvocationTargetException] An error occurred populating the IncidentRequisition object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }
        }

        // put more info in the requisition (description)?
        String failedStepId;
        String stepExecutionException = null;
        if (failedStep == null) {
            failedStepId = "Unknown";
            stepExecutionException = "Unknown Error";
        }
        else {
            failedStepId = failedStep.getStepId();
            @SuppressWarnings("unchecked")
            List<Property> failedStepProperties = failedStep.getProperty();
            for (Property p : failedStepProperties) {
                if (p.getKey().equals("stepExecutionException")) {
                    stepExecutionException = p.getValue();
                    break;
                }
            }
            if (stepExecutionException == null) {
                stepExecutionException = "Unknown Error";
            }
        }

        String d = incidentRequisition.getDescription();
        d = d.replaceAll("PROVISIONING_ID", getRoleDeprovisioning().getRoleDeprovisioningId());
        d = d.replaceAll("STEP_ID", failedStepId);
        d = d.replaceAll("ERROR_DESCRIPTION", stepExecutionException);

        try {
            incidentRequisition.setDescription(d);
        } catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the IncidentRequisition object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Log the state of the requisition.
        try {
            logger.info(LOGTAG + "Incident requisition is: " + incidentRequisition.toXmlString());
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getServiceNowServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        List<Incident> results;
        try {
            long queryStartTime = System.currentTimeMillis();
            results = incident.generate(incidentRequisition, rs);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            logger.info(LOGTAG + "Generated Incident in " + queryTime + "ms. There are " + results.size() + " result(s).");
        }
        catch (EnterpriseObjectGenerateException e) {
            String errMsg = "An error occurred generating for the Incident object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getServiceNowServiceProducerPool().releaseProducer((MessageProducer) rs);
        }

        // there should only be one result but log everything we got
        for (Incident i : results) {
            try {
                logger.info(LOGTAG + "Generated incident: " + i.toXmlString());
            }
            catch (XmlEnterpriseObjectException e) {
                String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
        }

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private ProducerPool getServiceNowServiceProducerPool() { return serviceNowServiceProducerPool; }
    private void setServiceNowServiceProducerPool(ProducerPool v) { this.serviceNowServiceProducerPool = v; }
    public boolean isCreateIncidentOnFailure() { return createIncidentOnFailure; }
    public void setCreateIncidentOnFailure(boolean v) { this.createIncidentOnFailure = v; }
}
