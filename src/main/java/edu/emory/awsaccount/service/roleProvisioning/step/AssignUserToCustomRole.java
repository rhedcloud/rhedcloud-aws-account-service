/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentRequisition;
import edu.emory.moa.objects.resources.v1_0.RoleDNs;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Add user to the custom role.
 */
public class AssignUserToCustomRole extends AbstractStep implements Step {
    private ProducerPool m_idmServiceProducerPool = null;
    private int m_requestTimeoutIntervalInMillis = 10000;
    private String m_identityDnTemplate = null;
    private String m_roleDnTemplate = null;

    public void init (String provisioningId, Properties props, AppConfig aConfig, RoleProvisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);
        
        String LOGTAG = getStepTag() + "[AssignUserToCustomRole.init] ";

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
        setIdentityDnTemplate(getMandatoryStringProperty(LOGTAG, "identityDnTemplate", false));
        setRoleDnTemplate(getMandatoryStringProperty(LOGTAG, "roleDnTemplate", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[AssignUserToCustomRole.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleProvisioningRequisition roleProvisioningRequisition = getRoleProvisioning().getRoleProvisioningRequisition();
        String accountId = roleProvisioningRequisition.getAccountId();
        String roleName = roleProvisioningRequisition.getRoleName();
        String roleAssigneeUserId = roleProvisioningRequisition.getRoleAssigneeUserId();

        // this step is optional and driven by the assignee field in the requisition
        if (roleAssigneeUserId == null || roleAssigneeUserId.isEmpty()) {
            addResultProperty("addedUserToCustomRole", "false");
        }
        else {
            // send a RoleAssignment.Generate-Request to add the user to the custom role
            generateRoleAssignment(roleAssigneeUserId, accountId, roleName);
            addResultProperty("addedUserToCustomRole", "true");
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
        String LOGTAG = getStepTag() + "[AssignUserToCustomRole.simulate] ";
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
        String LOGTAG = getStepTag() + "[AssignUserToCustomRole.fail] ";
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
        String LOGTAG = getStepTag() + "[AssignUserToCustomRole.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);
        
        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }
    
    private void generateRoleAssignment(String userId, String accountId, String roleName)
        throws StepException {
        
        String LOGTAG = getStepTag() + "[AssignUserToCustomRole.generateRoleAssignment] ";
        
        logger.info(LOGTAG + "Generating RoleAssignment for user " + userId  + " for account " + accountId);
        
        // Get a configured RoleAssignment from AppConfig.
        RoleAssignment ra;
        RoleAssignmentRequisition req;
        try {
            ra = (RoleAssignment) getAppConfig().getObjectByType(RoleAssignment.class.getName());
            req = (RoleAssignmentRequisition) getAppConfig().getObjectByType(RoleAssignmentRequisition.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        
        // Set the values of the RoleAssignmentRequisition
        try {
            req.setRoleAssignmentActionType("grant");
            req.setRoleAssignmentType("USER_TO_ROLE");
            req.setIdentityDN(buildIdentityDnFromTemplate(userId));
            req.setReason("user is in custom role");
            RoleDNs roleDns = req.newRoleDNs();
            roleDns.addDistinguishedName(buildRoleDnFromTemplate(accountId, roleName));
            req.setRoleDNs(roleDns);
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the requisition. The exception is " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        
        // Log the state of the RoleRequisition.
        try {
            logger.info(LOGTAG + "RoleAssignment req is: " + req.toXmlString());
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
        
        List<RoleAssignment> results;
        try { 
            long generateStartTime = System.currentTimeMillis();
            results = ra.generate(req, rs);
            long generateTime = System.currentTimeMillis() - generateStartTime;
            logger.info(LOGTAG + "Generated RoleAssignment in " + generateTime + " ms.");
        }
        catch (EnterpriseObjectGenerateException e) {
            String errMsg = "An error occurred generating the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getIdmServiceProducerPool().releaseProducer((MessageProducer)rs);
        }

        // there should only be one result but log everything we got
        for (RoleAssignment r : results) {
            try {
                logger.info(LOGTAG + "Generated RoleAssignment: " + r.toXmlString());
            }
            catch (XmlEnterpriseObjectException e) {
                String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
        }
    }
    
    private ProducerPool getIdmServiceProducerPool() { return m_idmServiceProducerPool; }
    private void setIdmServiceProducerPool(ProducerPool v) { m_idmServiceProducerPool = v; }
    private int getRequestTimeoutIntervalInMillis() { return m_requestTimeoutIntervalInMillis; }
    private void setRequestTimeoutIntervalInMillis(int v) { m_requestTimeoutIntervalInMillis = v; }
    private String getIdentityDnTemplate() { return m_identityDnTemplate; }
    private void setIdentityDnTemplate (String v) { m_identityDnTemplate = v; }
    private String getRoleDnTemplate() { return m_roleDnTemplate; }
    private void setRoleDnTemplate (String v) { m_roleDnTemplate = v; }

    private String buildIdentityDnFromTemplate(String userId) {
        return getIdentityDnTemplate().replace("USER_ID", userId);
    }
    
    private String buildRoleDnFromTemplate(String accountId, String roleName) {
        return getRoleDnTemplate().replace("ACCOUNT_NUMBER", accountId).replace("CUSTOM_ROLE_NAME", roleName);
    }
}
