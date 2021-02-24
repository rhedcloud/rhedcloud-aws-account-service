/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentRequisition;
import edu.emory.moa.objects.resources.v1_0.RoleDNs;

/**
 * If this is a new account, add admins to the admin role.
 * <P>
 *
 * @author Tod Jackson (jtjacks@emory.edu)
 * @version 1.0 - 6 November 2020
 **/
public class AssignToGrouperIdmGroup extends AbstractStep implements Step {

    private ProducerPool m_idmServiceProducerPool = null;
    private int m_requestTimeoutIntervalInMillis = 10000;
    private String identityDn = null;
    private String groupNameTemplate = null;
    private String siteName = null;
    private String cloudPlatformName = null;

    public void init (String provisioningId, Properties props,
            AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[AssignToGrouperIdmGroup.init] ";

        identityDn = getProperties().getProperty("identityDn", null);
        siteName = getProperties().getProperty("siteName", "Rice");
        cloudPlatformName = getProperties().getProperty("cloudPlatformName", "AWS");

        groupNameTemplate = getProperties().getProperty("groupNameTemplate");
        if (groupNameTemplate == null) {
            throw new StepException("Configuration error.  The "
                + "'groupNameTemplate' property is missing from this "
                + "AppConfig.  Should be in the format "
                + "'ACCOUNT_NUMBER:[group name]' where 'group name' is one "
                + "of 'admin', 'auditor' 'c_admin'.  Cannot continue.");
        }
        else {
            logger.info(LOGTAG + "groupNameTemplate is: " +    groupNameTemplate);
        }

        // This step needs to send messages to the IDM service
        // to create account roles.
        ProducerPool p2p1 = null;
        try {
            p2p1 = (ProducerPool)getAppConfig()
                .getObject("IdmServiceProducerPool");
            setIdmServiceProducerPool(p2p1);
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig. Log it and
            // throw an exception.
            String errMsg = "An error occurred retrieving an object from " +
                    "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        // Get custom step properties.
        logger.info(LOGTAG + "Getting custom step properties...");

        String requestTimeoutInterval = getProperties()
            .getProperty("requestTimeoutIntervalInMillis", "10000");
        int requestTimeoutIntervalInMillis = Integer.parseInt(requestTimeoutInterval);
        setRequestTimeoutIntervalInMillis(requestTimeoutIntervalInMillis);
        logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " +
            getRequestTimeoutIntervalInMillis());

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[AssignToGrouperIdmGroup.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        // Set result properties.
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        // Get some properties from previous steps.
        String allocateNewAccount = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
        String newAccountId = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");

        boolean allocatedNewAccount = Boolean.parseBoolean(allocateNewAccount) ;
        logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
        logger.info(LOGTAG + "newAccountId: " + newAccountId);

        // If allocatedNewAccount is true and newAccountId is not null,
        // Build a list of all account admins and send a RoleAssignment.Generate-Request
        // to add each admin to the admin role.
        if (allocatedNewAccount && (newAccountId != null && !newAccountId.equals(PROPERTY_VALUE_NOT_AVAILABLE))) {
            logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " +
                "is not null. Adding administrators to admin role.");

            // Build a list of administrators.
            VirtualPrivateCloudRequisition vpcr = getVirtualPrivateCloudProvisioning()
                .getVirtualPrivateCloudRequisition();

            // Get the requestor and the account owner.
            String requestorId = vpcr.getAuthenticatedRequestorUserId();
            String ownerId = vpcr.getAccountOwnerUserId();

            List adminUserIds = vpcr.getCustomerAdminUserId();

            if (adminUserIds == null) {
                adminUserIds = new ArrayList<String>();
            }

            adminUserIds.add(requestorId);
            adminUserIds.add(ownerId);

            logger.info(LOGTAG + "There are " + adminUserIds.size() + " admin user IDs.");

            List<String> distinctAdminUserIds = buildDistinctUserIdList(adminUserIds);

            logger.info(LOGTAG + "There are " + distinctAdminUserIds.size() +
                "distinct admin user IDs.");
            logger.info(LOGTAG + "Distinct AdminUserIds are: " +
                toUserIdListString(distinctAdminUserIds));

            ListIterator li = distinctAdminUserIds.listIterator();
            int i = 0;
            while (li.hasNext()) {
                String id = (String)li.next();
                generateRoleAssignment(id, newAccountId);
                i++;
            }

            logger.info(LOGTAG + "Generated " + i + " admin RoleAssignments.");
            addResultProperty("addedAdminsToAdminRole", "true");
            addResultProperty("distinctCentralAdminUsers",
                Integer.toString(distinctAdminUserIds.size()));
        }

        // Otherwise, add result properties and log that no action was required.
        else {
            logger.info(LOGTAG + "No new account was created. No need to add admins to " +
                "admin role.");
            addResultProperty("addedAdminsToAdminRole", "not applicable");
        }

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();

    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() +
            "[ExampleStep.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() +
            "[ExampleStep.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        // Update the step.
        update(COMPLETED_STATUS, FAILURE_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[ExampleStep.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void generateRoleAssignment(String userId, String accountId)
        throws StepException {

        String LOGTAG = getStepTag() + "[AssignAdminsToAdminrole.generateRoleAssignment] ";

        logger.info(LOGTAG + "Generating admin RoleAssignment for user " +
            userId  + " for account " + accountId);

        // Get a configured RoleAssignment from AppConfig.
        RoleAssignment ra = new RoleAssignment();
        RoleAssignmentRequisition req = new RoleAssignmentRequisition();
        try {
            ra = (RoleAssignment)getAppConfig()
                .getObjectByType(ra.getClass().getName());
            req = (RoleAssignmentRequisition)getAppConfig()
                .getObjectByType(req.getClass().getName());
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from " +
              "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }

        // Set the values of the RoleAssignmentRequisition
        try {
            req.setRoleAssignmentActionType("grant");
            req.setRoleAssignmentType("USER_TO_ROLE");
            if (identityDn != null) {
                // it's the org level c_admin assignment to account:c_admin
                // identityDn was passed in via AppConfig property because
                // it's a static GUID value
                req.setIdentityDN(identityDn);
            }
            else {
                // it's a normal user to role assignment
                req.setIdentityDN(userId);
            }
            req.setReason("user is an account administrator");
            RoleDNs roleDns = req.newRoleDNs();
            roleDns.addDistinguishedName(buildRoleDnFromTemplate(accountId));
            req.setRoleDNs(roleDns);
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting field values of the " +
                "requisition. The exception is " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Log the state of the RoleRequisition.
        try {
            logger.info(LOGTAG + "RoleAssignment req is: " +
                req.toXmlString());
        }
        catch (XmlEnterpriseObjectException xeoe) {
            String errMsg = "An error occurred serializing the object " +
                    "to XML. The exception is: " + xeoe.getMessage();
              logger.error(LOGTAG + errMsg);
              throw new StepException(errMsg, xeoe);
        }

        // Get a producer from the pool
        RequestService rs = null;
        try {
            PointToPointProducer p2p =
                (PointToPointProducer)getIdmServiceProducerPool()
                .getExclusiveProducer();
            p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
            rs = (RequestService)p2p;
        }
        catch (JMSException jmse) {
            String errMsg = "An error occurred getting a producer " +
                "from the pool. The exception is: " + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, jmse);
        }

        List results = null;
        try {
            long generateStartTime = System.currentTimeMillis();
            results = ra.generate(req, rs);
            long generateTime = System.currentTimeMillis() - generateStartTime;
            logger.info(LOGTAG + "Generated RoleAssignment in " + generateTime
                + " ms.");
        }
        catch (EnterpriseObjectGenerateException eoge) {
            String errMsg = "An error occurred generating the object. " +
              "The exception is: " + eoge.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoge);
        }
        finally {
            // Release the producer back to the pool
            getIdmServiceProducerPool()
                .releaseProducer((MessageProducer)rs);
        }

        // If there is exactly one result, log it.
        if (results.size() == 1) {
            ra = (RoleAssignment)results.get(0);
            try {
                logger.info(LOGTAG + "Generated RoleAssignment: " +
                    ra.toXmlString());
            }
            catch (XmlEnterpriseObjectException xeoe) {
                String errMsg = "An error occurred serializing the object " +
                        "to XML. The exception is: " + xeoe.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, xeoe);
            }
        }
    }

    private void setIdmServiceProducerPool(ProducerPool pool) {
        m_idmServiceProducerPool = pool;
    }

    private ProducerPool getIdmServiceProducerPool() {
        return m_idmServiceProducerPool;
    }

    private void setRequestTimeoutIntervalInMillis(int time) {
        m_requestTimeoutIntervalInMillis = time;
    }

    private int getRequestTimeoutIntervalInMillis() {
        return m_requestTimeoutIntervalInMillis;
    }

    private String buildRoleDnFromTemplate(String accountId) {
        String dn = groupNameTemplate.replace("ACCOUNT_NUMBER", accountId);
        return dn;
    }

    private String toUserIdListString(List ids) {

        String list = "";

        ListIterator<String> li = ids.listIterator();
        while (li.hasNext()) {
            String id = (String)li.next();
            list = list + id;
            if (li.hasNext()) {
                list = list + ", ";
            }
        }

        return list;
    }

    private List<String> buildDistinctUserIdList(List userIds) {

        List<String> distinctUserIds = new ArrayList<String>();

        ListIterator li = userIds.listIterator();
        while (li.hasNext()) {
            String id = (String)li.next();
            if (distinctUserIds.contains(id) == false) {
                distinctUserIds.add(id);
            }
        }

        return distinctUserIds;
    }

}
