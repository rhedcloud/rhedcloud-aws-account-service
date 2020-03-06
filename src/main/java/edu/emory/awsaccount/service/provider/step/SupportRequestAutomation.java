/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2020 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.*;
import com.amazonaws.services.support.AWSSupportClient;
import com.amazonaws.services.support.model.CreateCaseRequest;
import com.amazonaws.services.support.model.CreateCaseResult;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.openeai.config.AppConfig;

import java.util.List;
import java.util.ListIterator;
import java.util.Properties;


public class SupportRequestAutomation extends AbstractStep implements Step {

    private final static String IN_PROGRESS = "IN_PROGRESS";
    private final static String SUCCEEDED = "SUCCEEDED";
    private final static String FAILED = "FAILED";
    private String m_pendingDeleteOuId = null;
    private String m_accountSeriesName = null;
    private String m_accessKey = null;
    private String m_secretKey = null;
    private AWSOrganizationsClient m_awsOrganizationsClient = null;
    private AWSSupportClient awsSupportClient = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[SupportRequestAutomation.init] ";

        // Get custom step properties.
        logger.info(LOGTAG + "Getting custom step properties...");

        String pendingDeleteOuId = getProperties().getProperty("pendingDeleteOuId", null);
        setPendingDeleteOuId(pendingDeleteOuId);

        String accountSeriesName = getProperties().getProperty("accountSeriesName", null);
        setAccountSeriesName(accountSeriesName);

        logger.info(LOGTAG + "accountSeriesName is: " + getAccountSeriesName());

        String accessKey = getProperties().getProperty("accessKey", null);
        setAccessKey(accessKey);
        logger.info(LOGTAG + "accessKey is: " + getAccessKey());

        String secretKey = getProperties().getProperty("secretKey", null);
        setSecretKey(secretKey);
        logger.info(LOGTAG + "secretKey is: present");

        // Set the AWS account credentials
        BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);

        // Instantiate an AWS client builder
        AWSOrganizationsClientBuilder builder = AWSOrganizationsClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(awsCredentials));
        builder.setRegion("us-east-1");

        AWSSupportClient awssclient = (AWSSupportClient)builder.build();
        awsSupportClient = awssclient;

        // Initialize the AWS client
        logger.info("Initializing AmazonCloudFormationClient...");
        AWSOrganizationsClient client = (AWSOrganizationsClient)builder.build();
        logger.info("AWSOrganizationsClient initialized.");

        setAwsOrganizationsClient(client);
        ListAccountsRequest request = new ListAccountsRequest();

        // Perform a test query
        ListAccountsResult result = client.listAccounts(request);
        logger.info(LOGTAG + "List accounts result: " + result.toString());

        // Set the client
        setAwsOrganizationsClient(client);

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[SupportRequestAutomation.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
        addResultProperty("accountSeriesName", getAccountSeriesName());

        // CREATE_SUPPORT_CASE step. THIS IS WHERE WE ADD THE NEW STEP NAME
        logger.info(LOGTAG + "Getting properties from preceding steps...");
        ProvisioningStep step2 = getProvisioningStepByType("CREATE_SUPPORT_CASE");
        String accountSequenceNumber = null;
        if (step2 != null) {
            logger.info(LOGTAG + "Step CREATE_SUPPORT_CASE found.");
            accountSequenceNumber = getResultProperty(step2, "accountSequenceNumber");
            addResultProperty("accountSequenceNumber", accountSequenceNumber);
            logger.info(LOGTAG + "Property accountSequenceNumber from preceding step is: " + accountSequenceNumber);
        } else {
            String errMsg = "Step CREATE_SUPPORT_CASE not found. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

		VirtualPrivateCloudProvisioning virtualPrivateCloudProvisioning = getVirtualPrivateCloudProvisioning();
		VirtualPrivateCloudRequisition virtualPrivateCloudRequisition = virtualPrivateCloudProvisioning.getVirtualPrivateCloudRequisition();

		CreateCaseRequest caseRequest = new CreateCaseRequest()
				.withServiceCode("account-management")
				.withCategoryCode("billing")
				.withLanguage("en")
				.withCcEmailAddresses("")
				.withCommunicationBody("A recently created linked/child account has been created as part of our organization. Please add account ".concat(virtualPrivateCloudRequisition.getFinancialAccountNumber()).concat(" to Enterprise Support"))
				.withSubject("Please add child/linked account ".concat(virtualPrivateCloudRequisition.getFinancialAccountNumber()).concat(" to Enterprise Support"))
				.withSeverityCode("normal");

		CreateCaseResult result = awsSupportClient.createCase(caseRequest);

        String stepResult = FAILURE_RESULT;
        String caseId = result.getCaseId();
        if (caseId != null && !caseId.equals("")) {
            stepResult = SUCCESS_RESULT;
        }

        update(COMPLETED_STATUS, stepResult);
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() +
                "[GenerateNewAccount.simulate] ";
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
                "[GenerateNewAccount.fail] ";
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

        super.rollback();

        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() +
                "[GenerateNewAccount.rollback] ";

        logger.info(LOGTAG + "Rollback called, if a new account was " +
                "created successfully and if it is still in the destination ou, "
                + "will attempt to move it to the pending delete ou.");

        // Get the result props
        List<Property> props = getResultProperties();

        // Get the createdNewAccount and account number properties
        boolean createdNewAccount = Boolean
                .getBoolean(getResultProperty("createdNewAccount"));
        String newAccountId = getResultProperty("newAccountId");
        boolean isAccountInOrgRoot = false;
        boolean movedAccountToPendingDeleteOu = false;

        // If newAccountId is not null, determine if the account is still in
        // the destination ou.
        if (newAccountId != null) {
            try {
                ListAccountsForParentRequest request = new ListAccountsForParentRequest();
                ListAccountsForParentResult result =
                        getAwsOrganizationsClient().listAccountsForParent(request);
                List<com.amazonaws.services.organizations.model.Account> accounts =
                        result.getAccounts();
                ListIterator<com.amazonaws.services.organizations.model.Account> li =
                        accounts.listIterator();
                while (li.hasNext()) {
                    com.amazonaws.services.organizations.model.Account account =
                            (com.amazonaws.services.organizations.model.Account) li.next();
                    if (account.getId().equalsIgnoreCase(newAccountId)) ;
                    isAccountInOrgRoot = true;
                }
            } catch (Exception e) {
                String errMsg = "An error occurred querying for a list of " +
                        "accounts in the org root. The exception is: " +
                        e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
        }

        // If the createdNewAccount is true and isAccountInOrgRoot is true,
        // move the account to the pending delete org unit.
        if (createdNewAccount && isAccountInOrgRoot) {
            // Build the request.
            MoveAccountRequest request = new MoveAccountRequest();
            request.setAccountId(newAccountId);

            // Send the request.
            try {
                logger.info(LOGTAG + "Sending the move account request...");
                long moveStartTime = System.currentTimeMillis();
                MoveAccountResult result = getAwsOrganizationsClient().moveAccount(request);
                long moveTime = System.currentTimeMillis() - moveStartTime;
                logger.info(LOGTAG + "received response to move account request in " +
                        moveTime + " ms.");
                movedAccountToPendingDeleteOu = true;
            } catch (Exception e) {
                String errMsg = "An error occurred moving the account. " +
                        "The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            addResultProperty("movedAccountToPendingDeleteOu",
                    Boolean.toString(movedAccountToPendingDeleteOu));

        }
        // If createdNewAccount or isAccountInOrgRoot is false, there is
        // nothing to roll back. Log it.
        else {
            logger.info(LOGTAG + "No account was created or it is no longer " +
                    "in the organization root, so there is nothing to roll back.");
            addResultProperty("movedAccountToPendingDeleteOu",
                    "not applicable");
        }

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private AWSOrganizationsClient getAwsOrganizationsClient() {
        return m_awsOrganizationsClient;
    }

    private void setAwsOrganizationsClient(AWSOrganizationsClient client) {
        m_awsOrganizationsClient = client;
    }

    private String getAccountSeriesName() {
        return m_accountSeriesName;
    }

    private void setAccountSeriesName(String name) throws
            StepException {

        if (name == null) {
            String errMsg = "accountSeriesName property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_accountSeriesName = name;
    }

    private String getAccessKey() {
        return m_accessKey;
    }

    private void setAccessKey(String accessKey) throws
            StepException {

        if (accessKey == null) {
            String errMsg = "accessKey property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_accessKey = accessKey;
    }

    private String getSecretKey() {
        return m_secretKey;
    }

    private void setSecretKey(String secretKey) throws
            StepException {

        if (secretKey == null) {
            String errMsg = "secretKey property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_secretKey = secretKey;
    }

    private void setPendingDeleteOuId(String id) throws
            StepException {

        if (id == null) {
            String errMsg = "pendingDeleteOuId property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_pendingDeleteOuId = id;
    }

}
