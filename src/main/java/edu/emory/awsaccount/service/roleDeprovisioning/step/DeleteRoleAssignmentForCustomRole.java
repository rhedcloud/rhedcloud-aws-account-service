/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Delete all Role Assignments from the custom role.
 */
public class DeleteRoleAssignmentForCustomRole extends AbstractStep implements Step {
    private ProducerPool m_idmServiceProducerPool = null;
    private int m_requestTimeoutIntervalInMillis = 10000;
    private String m_roleDnTemplate = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[DeleteRoleAssignmentForCustomRole.init] ";

        // This step needs to send messages to the IDM service to create account roles.
        try {
            ProducerPool p = (ProducerPool)getAppConfig().getObject("IdmServiceProducerPool");
            setIdmServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Getting custom step properties...");

        setRequestTimeoutIntervalInMillis(getWithDefaultIntegerProperty(LOGTAG, "requestTimeoutIntervalInMillis", "10000", false));
        setRoleDnTemplate(getMandatoryStringProperty(LOGTAG, "roleDnTemplate", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteRoleAssignmentForCustomRole.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleDeprovisioningRequisition requisition = getRoleDeprovisioning().getRoleDeprovisioningRequisition();
        String accountId = requisition.getAccountId();
        String roleName = requisition.getRoleName();

        // Get configured objects from AppConfig.
        RoleAssignment roleAssignment;
        RoleAssignmentQuerySpecification querySpec;
        try {
            roleAssignment = (RoleAssignment) getAppConfig().getObjectByType(RoleAssignment.class.getName());
            querySpec = (RoleAssignmentQuerySpecification) getAppConfig().getObjectByType(RoleAssignmentQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            querySpec.setRoleDN(buildRoleDnFromTemplate(accountId, roleName));
            querySpec.setIdentityType("USER");
            querySpec.setDirectAssignOnly("true");

            logger.info(LOGTAG + "RoleAssignmentQuerySpecification is: " + querySpec.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the query spec. The exception is " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            PointToPointProducer p2p = (PointToPointProducer) getIdmServiceProducerPool().getExclusiveProducer();
            p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
            rs = p2p;
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<RoleAssignment> results = roleAssignment.query(querySpec, rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "Queried RoleAssignment in " + elapsedTime + " ms.");

            int index = 1;
            for (RoleAssignment ra : results) {
                try {
                    logger.info(LOGTAG + "RoleAssignment to be deleted is: " + ra.toXmlString());
                }
                catch (XmlEnterpriseObjectException e) {
                    String errMsg = "An error occurred serializing the RoleAssignment to be deleted to XML. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, e);
                }

                String dn = String.join("||", ra.getExplicitIdentityDNs().getDistinguishedName());

                elapsedStartTime = System.currentTimeMillis();
                ra.delete("Delete", rs);
                elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                logger.info(LOGTAG + "Deleted RoleAssignment in " + elapsedTime + " ms.");

                addResultProperty("deletedRoleAssignment" + index++, dn);
            }

            if (index == 1) {
                addResultProperty("deletedRoleAssignment", "not applicable");
            }
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (EnterpriseObjectDeleteException e) {
            String errMsg = "An error occurred deleting the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getIdmServiceProducerPool().releaseProducer((MessageProducer)rs);
        }

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
        String LOGTAG = getStepTag() + "[DeleteRoleAssignmentForCustomRole.simulate] ";
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
        String LOGTAG = getStepTag() + "[DeleteRoleAssignmentForCustomRole.fail] ";
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
        long startTime = System.currentTimeMillis();
        super.rollback();
        String LOGTAG = getStepTag() + "[DeleteRoleAssignmentForCustomRole.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private ProducerPool getIdmServiceProducerPool() { return m_idmServiceProducerPool; }
    private void setIdmServiceProducerPool(ProducerPool v) { m_idmServiceProducerPool = v; }
    private int getRequestTimeoutIntervalInMillis() { return m_requestTimeoutIntervalInMillis; }
    private void setRequestTimeoutIntervalInMillis(int v) { m_requestTimeoutIntervalInMillis = v; }
    private String getRoleDnTemplate() { return m_roleDnTemplate; }
    private void setRoleDnTemplate (String v) { m_roleDnTemplate = v; }

    private String buildRoleDnFromTemplate(String accountId, String roleName) {
        return getRoleDnTemplate().replace("ACCOUNT_NUMBER", accountId).replace("CUSTOM_ROLE_NAME", roleName);
    }
}
