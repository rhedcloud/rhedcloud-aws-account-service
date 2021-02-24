/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.EmailAddress;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * If this is a new account request, create account metadata
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 30 August 2018
 **/
public class CreateAccountMetadata extends AbstractStep implements Step {

    private ProducerPool m_awsAccountServiceProducerPool = null;
    private String m_passwordLocation = null;

    public void init(String provisioningId, Properties props,
                     AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[CreateAccountMetadata.init] ";

        // This step needs to send messages to the AWS account service
        // to create account metadata.
        ProducerPool p2p1 = null;
        try {
            p2p1 = (ProducerPool) getAppConfig()
                    .getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p2p1);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig. Log it and
            // throw an exception.
            String errMsg = "An error occurred retrieving an object from " +
                    "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        String passwordLocation = getProperties().getProperty("passwordLocation", null);
        setPasswordLocation(passwordLocation);
        logger.info(LOGTAG + "passwordLocation is: " + getPasswordLocation());

        logger.info(LOGTAG + "Initialization complete.");

    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateAccountMetadata.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        boolean accountMetadataCreated = false;

        // Return properties
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        String allocateNewAccount = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
        String newAccountId = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");

        boolean allocatedNewAccount = Boolean.parseBoolean(allocateNewAccount) ;
        logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
        logger.info(LOGTAG + "newAccountId: " + newAccountId);

        // If allocatedNewAccount is true and newAccountId is not null,
        // Send an Account.Create-Request to the AWS Account service.
        if (allocatedNewAccount && (newAccountId != null && !newAccountId.equals(PROPERTY_VALUE_NOT_AVAILABLE))) {
            logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " +
                    "is not null. Sending an Account.Create-Request to create account metadata.");

            addResultProperty("newAccountId", newAccountId);

            String newAccountName = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountName");
            String accountEmailAddress = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "accountEmailAddress");

            // Get a configured account object from AppConfig.
            Account account = new Account();
            try {
                account = (Account) getAppConfig().getObjectByType(account.getClass().getName());
            } catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, ecoe);
            }

            // Get the VPCP requisition object.
            VirtualPrivateCloudProvisioning vpcp = getVirtualPrivateCloudProvisioning();
            VirtualPrivateCloudRequisition req = vpcp.getVirtualPrivateCloudRequisition();

            // Set the values of the account.
            try {
                account.setAccountId(newAccountId);
                account.setAccountName(newAccountName);
                account.setAccountOwnerId(req.getAccountOwnerUserId());
                account.setComplianceClass(req.getComplianceClass());
                account.setPasswordLocation(getPasswordLocation());
                account.setFinancialAccountNumber(req.getFinancialAccountNumber());

                EmailAddress primaryEmailAddress = account.newEmailAddress();
                primaryEmailAddress.setType("primary");
                primaryEmailAddress.setEmail(accountEmailAddress);
                account.addEmailAddress(primaryEmailAddress);

                EmailAddress operationsEmailAddress = account.newEmailAddress();
                operationsEmailAddress.setType("operations");
                operationsEmailAddress.setEmail(accountEmailAddress);
                account.addEmailAddress(operationsEmailAddress);

//                EmailAddress securityEmailAddress = account.newEmailAddress();
//                securityEmailAddress.setType("security");
//                securityEmailAddress.setEmail(securityEmailAddress);
//                account.addEmailAddress(securityEmailAddress);

                account.setCreateUser(req.getAuthenticatedRequestorUserId());
                Datetime createDatetime = new Datetime("Create", System.currentTimeMillis());
                account.setCreateDatetime(createDatetime);

                // Set the account to be SRD exempt initially.
                // This will be changed later in the provisioning.
                Property prop1 = account.newProperty();
                prop1.setKey("srdExempt");
                prop1.setValue("true");
                account.addProperty(prop1);

                // Set the initialProvisioningId property.
                Property prop2 = account.newProperty();
                prop2.setKey("initialProvisioningId");
                prop2.setValue(vpcp.getProvisioningId());
                account.addProperty(prop2);

            } catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of the query spec. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, efe);
            }

            // Log the state of the account.
            try {
                logger.info(LOGTAG + "Account to create is: " + account.toXmlString());
            } catch (XmlEnterpriseObjectException xeoe) {
                String errMsg = "An error occurred serializing the query spec to XML. The exception is: " + xeoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, xeoe);
            }

            // Get a producer from the pool
            RequestService rs;
            try {
                rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
            } catch (JMSException jmse) {
                String errMsg = "An error occurred getting a producer from the pool. The exception is: " + jmse.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, jmse);
            }

            try {
                long createStartTime = System.currentTimeMillis();
                account.create(rs);
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "Create Account in " + createTime + " ms.");
                accountMetadataCreated = true;
                addResultProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount));
                addResultProperty("createdAccountMetadata", Boolean.toString(accountMetadataCreated));
            } catch (EnterpriseObjectCreateException eoce) {
                String errMsg = "An error occurred creating the object. The exception is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, eoce);
            } finally {
                getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
            }

        }
        // If allocatedNewAccount is false, log it and add result props.
        else {
            logger.info(LOGTAG + "allocatedNewAccount is false. no need to create account metadata.");
            addResultProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount));
            addResultProperty("createdAccountMetadata", "not applicable");
        }

        // Update the step result.
        String stepResult = FAILURE_RESULT;
        if (accountMetadataCreated == true && allocatedNewAccount == true) {
            stepResult = SUCCESS_RESULT;
        }
        if (allocatedNewAccount == false) {
            stepResult = SUCCESS_RESULT;
        }

        // Update the step.
        update(COMPLETED_STATUS, stepResult);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateAccountMetadata.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("accountMetadataCreated", "true");

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
        String LOGTAG = getStepTag() + "[CreateAccountMetadata.fail] ";
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

        super.rollback();

        String LOGTAG = getStepTag() + "[CreateAccountMetadata.rollback] ";
        logger.info(LOGTAG + "Rollback called, deleting account metadata.");

        // Get the account number
        String newAccountId = getResultProperty("newAccountId");

        // If the newAccountId is not null, query for the account object and then delete it.
        if (newAccountId != null) {
            Account account = new Account();
            AccountQuerySpecification querySpec = new AccountQuerySpecification();
            try {
                account = (Account) getAppConfig().getObjectByType(account.getClass().getName());
                querySpec = (AccountQuerySpecification) getAppConfig().getObjectByType(querySpec.getClass().getName());
            } catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, ecoe);
            }

            // Set the values of the query spec
            try {
                querySpec.setAccountId(newAccountId);
            } catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting a field value. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException();
            }

            // Get a producer from the pool
            RequestService rs;
            try {
                rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
            } catch (JMSException jmse) {
                String errMsg = "An error occurred getting a producer from the pool. The exception is: " + jmse.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, jmse);
            }

            // Query for the account metadata
            List results;
            try {
                long queryStartTime = System.currentTimeMillis();
                results = account.query(querySpec, rs);
                long createTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for Account in " + createTime + " ms. Got " + results.size() + " result(s).");
            } catch (EnterpriseObjectQueryException eoqe) {
                String errMsg = "An error occurred querying for the object. The exception is: " + eoqe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, eoqe);
            } finally {
                getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
            }

            // If there is a result, delete the account metadata
            if (results.size() > 0) {
                account = (Account) results.get(0);

                try {
                    rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
                } catch (JMSException jmse) {
                    String errMsg = "An error occurred getting a producer from the pool. The exception is: " + jmse.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, jmse);
                }

                // Delete the account metadata
                try {
                    long deleteStartTime = System.currentTimeMillis();
                    account.delete("Delete", rs);
                    long deleteTime = System.currentTimeMillis() - deleteStartTime;
                    logger.info(LOGTAG + "Deleted Account in " + deleteTime + " ms. Got " + results.size() + " result(s).");
                    addResultProperty("deletedAccountMetadataOnRollback", "true");
                } catch (EnterpriseObjectDeleteException eode) {
                    String errMsg = "An error occurred deleting the object. The exception is: " + eode.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, eode);
                } finally {
                    getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
                }
            }
        }
        else {
            logger.info(LOGTAG + "No account metadata was created by this step, so there is nothing to roll back.");
            addResultProperty("deletedAccountMetadataOnRollback", "not applicable");
        }

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void setAwsAccountServiceProducerPool(ProducerPool pool) {
        m_awsAccountServiceProducerPool = pool;
    }

    private ProducerPool getAwsAccountServiceProducerPool() {
        return m_awsAccountServiceProducerPool;
    }

    private void setPasswordLocation(String loc) throws StepException {
        if (loc == null) {
            String errMsg = "passwordLocation property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_passwordLocation = loc;
    }

    private String getPasswordLocation() {
        return m_passwordLocation;
    }
}
