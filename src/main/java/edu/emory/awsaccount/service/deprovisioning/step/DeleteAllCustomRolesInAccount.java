/* *****************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.CustomRole;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.RoleDeprovisioning;
import com.amazon.aws.moa.objects.resources.v1_0.CustomRoleQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.lightweightdirectoryservices.v1_0.OrganizationalUnit;
import edu.emory.moa.objects.resources.v1_0.OrganizationalUnitQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;


/**
 * Delete all Custom Roles in the Account.
 */
public class DeleteAllCustomRolesInAccount extends AbstractStep implements Step {
    private ProducerPool awsAccountServiceProducerPool;
    private ProducerPool ldsServiceProducerPool = null;
    private String organizationalUnitDnTemplate;

    public void init (String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider vpcpp) throws StepException {
        super.init(deprovisioningId, props, aConfig, vpcpp);
        String LOGTAG = getStepTag() + "[DeleteAllCustomRolesInAccount.init] ";

        // This step needs to send messages to the AWS account service to deprovision CustomRoles.
        try {
            ProducerPool p = (ProducerPool)getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        // This step needs to send messages to the LDS Service
        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("LdsServiceProducerPool");
            setLdsServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Getting custom step properties...");

        String organizationalUnitDnTemplate = getProperties().getProperty("organizationalUnitDnTemplate", null);
        if (organizationalUnitDnTemplate == null || organizationalUnitDnTemplate.equals("")) {
            throw new StepException("No organizationalUnitDnTemplate property specified. Can't continue.");
        }
        setOrganizationalUnitDnTemplate(organizationalUnitDnTemplate);
        logger.info(LOGTAG + "organizationalUnitDnTemplate is: " + organizationalUnitDnTemplate);

        logger.info(LOGTAG + "Initialization complete.");
    }

    private List<String> getCustomRoleNamesInAccount(String accountId, String LOGTAG) throws StepException {
        CustomRole customRole;
        CustomRoleQuerySpecification querySpec;
        try {
            customRole = (CustomRole) getAppConfig().getObjectByType(CustomRole.class.getName());
            querySpec = (CustomRoleQuerySpecification) getAppConfig().getObjectByType(CustomRoleQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting CustomRole properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        try {
            querySpec.setAccountId(accountId);
            logger.info(LOGTAG + "CustomRoleQuerySpecification is: " + querySpec.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the CustomRoleQuerySpecification object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the CustomRoleQuerySpecification to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<CustomRole> results = customRole.query(querySpec, rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "CustomRole query took " + elapsedTime + " ms.");

            return results.stream().map(CustomRole::getRoleName).collect(Collectors.toList());
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying the CustomRole. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer)rs);
        }
    }

    private void roleDeprovisioningGenerate(String accountId, String roleName, String LOGTAG) throws StepException {
        RoleDeprovisioning roleDeprovisioning;
        RoleDeprovisioningRequisition requisition;
        RoleDeprovisioningQuerySpecification querySpec;

        try {
            roleDeprovisioning = (RoleDeprovisioning) getAppConfig().getObjectByType(RoleDeprovisioning.class.getName());
            requisition = (RoleDeprovisioningRequisition) getAppConfig().getObjectByType(RoleDeprovisioningRequisition.class.getName());
            querySpec = (RoleDeprovisioningQuerySpecification) getAppConfig().getObjectByType(RoleDeprovisioningQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting objects from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        try {
            requisition.setAccountId(accountId);
            requisition.setRoleName(roleName);
            logger.info(LOGTAG + "RoleDeprovisioningRequisition is: " + requisition.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the RoleDeprovisioningRequisition object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the RoleDeprovisioningRequisition to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        /*
         * Deprovision Custom Roles by sending RoleDeprovisioning.Generate-Request messages (to ourselves).
         * Once the deprovisioning has started, poll the status until it's complete (but don't wait endlessly).
         */

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        String roleDeprovisioningId;
        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<RoleDeprovisioning> results = roleDeprovisioning.generate(requisition, rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "RoleDeprovisioning generate took " + elapsedTime + " ms.");

            if (results.size() != 1) {
                String errMsg = "Unexpected number of RoleDeprovisioning results. Found " + results.size() + ". Expected 1.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

            roleDeprovisioningId = results.get(0).getRoleDeprovisioningId();
        }
        catch (EnterpriseObjectGenerateException e) {
            String errMsg = "An error occurred generate the RoleDeprovisioning. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer)rs);
        }

        try {
            querySpec.setRoleDeprovisioningId(roleDeprovisioningId);
            logger.info(LOGTAG + "RoleDeprovisioningQuerySpecification is: " + querySpec.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the RoleDeprovisioningQuerySpecification object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the RoleDeprovisioningQuerySpecification to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        final long waitingForCompleteStartTime = System.currentTimeMillis();
        do {
            // don't wait for ever
            if ((waitingForCompleteStartTime + 200_000) < System.currentTimeMillis()) {
                String errMsg = "Took too long waiting for RoleDeprovisioning completion status.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

            // Get a producer from the pool
            try {
                rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
            }
            catch (JMSException e) {
                String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            try {
                long elapsedStartTime = System.currentTimeMillis();
                @SuppressWarnings("unchecked")
                List<RoleDeprovisioning> results = roleDeprovisioning.query(querySpec, rs);
                long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                logger.info(LOGTAG + "RoleDeprovisioning query took " + elapsedTime + " ms.");

                if (results.size() != 1) {
                    String errMsg = "Unexpected number of RoleDeprovisioning results. Found " + results.size() + ". Expected 1.";
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg);
                }

                // if deprovisioning for an individual custom role fails then this entire step
                // will fail because deprovisioning of the account would subsequently fail. so it's
                // better to fail here with a known reason instead of later for a strange reason
                // i.e., later when the account is deleted from LDS it fails with CANT_ON_NON_LEAF
                String status = results.get(0).getStatus();
                if ("completed".equals(status)) {
                    String deprovisioningResult = results.get(0).getDeprovisioningResult();
                    if (!"success".equals(deprovisioningResult)) {
                        String errMsg = "RoleDeprovisioning for role name " + roleName + " was not successful";
                        logger.error(LOGTAG + errMsg);
                        throw new StepException(errMsg);
                    }
                    logger.info(LOGTAG + "RoleDeprovisioning for role name " + roleName + " was successful");
                    return;
                }

                // sleep before checking again
                logger.info(LOGTAG + "RoleDeprovisioning for role name " + roleName + " is waiting for complete status");
                Thread.sleep(5000);
            }
            catch (EnterpriseObjectQueryException e) {
                String errMsg = "An error occurred query the RoleDeprovisioning. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
            catch (InterruptedException e) {
                String errMsg = "Sleep was interrupted while waiting for RoleDeprovisioning completion status. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
            finally {
                getAwsAccountServiceProducerPool().releaseProducer((MessageProducer)rs);
            }
        } while (true);
    }

    private void deleteCustomRolesOrganizationalUnit(String accountId, String LOGTAG) throws StepException {
        OrganizationalUnit ou;
        OrganizationalUnitQuerySpecification ouQuerySpec;
        try {
            ou = (OrganizationalUnit) getAppConfig().getObjectByType(OrganizationalUnit.class.getName());
            ouQuerySpec = (OrganizationalUnitQuerySpecification) getAppConfig().getObjectByType(OrganizationalUnitQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            ouQuerySpec.setdistinguishedName(fromTemplate(getOrganizationalUnitDnTemplate(), accountId));
            logger.info(LOGTAG + "OrganizationalUnitQuerySpecification is: " + ouQuerySpec.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the object. The exception is: " + e.getMessage();
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
            rs = (RequestService) getLdsServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<OrganizationalUnit> results = ou.query(ouQuerySpec, rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "OrganizationalUnit query took " + elapsedTime + " ms. There are " + results.size() + " result(s).");

            /*
             * there is a failure mode we're not coding for.
             * if the custom role metadata gets out of sync with what is in LDS then there could still be entries
             * under OU=customroles and that would cause this next delete call to fail (CANT_ON_NON_LEAF).
             */
            if (results.size() == 1) {
                elapsedStartTime = System.currentTimeMillis();
                results.get(0).delete("Delete", rs);
                elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                logger.info(LOGTAG + "OrganizationalUnit delete took " + elapsedTime + " ms.");
            }
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying for the OU object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (EnterpriseObjectDeleteException e) {
            String errMsg = "An error occurred deleting the OU object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getLdsServiceProducerPool().releaseProducer((MessageProducer) rs);
        }
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteAllCustomRolesInAccount.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        List<String> customRoleNamesInAccount = getCustomRoleNamesInAccount(accountId, LOGTAG);
        if (customRoleNamesInAccount.size() == 0) {
            logger.info(LOGTAG + "There are no custom roles in account");
            addResultProperty("customRolesInAccount", "none");
        }
        else {
            logger.info(LOGTAG + "Custom roles in account are " + String.join(" and ", customRoleNamesInAccount));
            for (int i = 0; i < customRoleNamesInAccount.size(); i++) {
                addResultProperty("customRolesInAccount" + i, customRoleNamesInAccount.get(i));
            }
        }

        // deprovision each custom role
        for (String roleName : customRoleNamesInAccount) {
            roleDeprovisioningGenerate(accountId, roleName, LOGTAG);
        }
        // then delete the OU=customroles under the account
        deleteCustomRolesOrganizationalUnit(accountId, LOGTAG);

        // Set return properties.
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

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
        String LOGTAG = getStepTag() + "[DeleteAllCustomRolesInAccount.simulate] ";
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
        String LOGTAG = getStepTag() + "[DeleteAllCustomRolesInAccount.fail] ";
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
        String LOGTAG = getStepTag() + "[DeleteAllCustomRolesInAccount.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private ProducerPool getAwsAccountServiceProducerPool() { return awsAccountServiceProducerPool; }
    private void setAwsAccountServiceProducerPool(ProducerPool v) { this.awsAccountServiceProducerPool = v; }
    private ProducerPool getLdsServiceProducerPool() { return ldsServiceProducerPool; }
    private void setLdsServiceProducerPool(ProducerPool v) { ldsServiceProducerPool = v; }
    public String getOrganizationalUnitDnTemplate() { return organizationalUnitDnTemplate; }
    public void setOrganizationalUnitDnTemplate(String v) { organizationalUnitDnTemplate = v; }

    private String fromTemplate(String template, String accountId) {
        return template.replace("ACCOUNT_NUMBER", accountId);
    }
}
