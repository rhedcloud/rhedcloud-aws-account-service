package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.organizations.AWSOrganizations;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.Account;
import com.amazonaws.services.organizations.model.ListAccountsForParentRequest;
import com.amazonaws.services.organizations.model.ListAccountsForParentResult;
import com.amazonaws.services.organizations.model.ListOrganizationalUnitsForParentRequest;
import com.amazonaws.services.organizations.model.ListOrganizationalUnitsForParentResult;
import com.amazonaws.services.organizations.model.ListRootsRequest;
import com.amazonaws.services.organizations.model.ListRootsResult;
import com.amazonaws.services.organizations.model.MoveAccountRequest;
import com.amazonaws.services.organizations.model.OrganizationalUnit;
import com.amazonaws.services.organizations.model.Root;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import org.openeai.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Move the account into the specified organization.
 */
public class MoveAccountToNewDestination extends AbstractStep implements Step {
    private String accessKeyId = null;
    private String secretKey = null;
    private String destinationParentName = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp)
            throws StepException {

        super.init(provisioningId, props, aConfig, adp);

        String LOGTAG = getStepTag() + "[MoveAccountToNewDestination.init] ";

        // Get custom step properties.
        logger.info(LOGTAG + "Getting custom step properties...");

        String accessKeyId = getProperties().getProperty("accessKeyId");
        setAccessKeyId(accessKeyId);
        logger.info(LOGTAG + "accessKeyId is: " + getAccessKeyId());

        String secretKey = getProperties().getProperty("secretKey");
        setSecretKey(secretKey);
        logger.info(LOGTAG + "secretKey is: present");

        String destinationParentName = getProperties().getProperty("destinationParentName");
        setDestinationParentName(destinationParentName);
        logger.info(LOGTAG + "destinationParentName is: " + getDestinationParentName());

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[MoveAccountToNewDestination.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();

        try {
            AWSOrganizationsClient orgClient = buildOrganizationsClient();

            // first step is to find the current parent of the account (SourceParentId)
            // and then find the ID of the destination based on the name provided (DestinationParentId)
            String sourceParentId = null;
            String destinationParentId = null;

            List<OrganizationalUnit> organizationalUnits = new ArrayList<>();

            ListRootsRequest listRootsRequest = new ListRootsRequest();
            ListRootsResult listRootsResult;
            do {
                listRootsResult = orgClient.listRoots(listRootsRequest);

                for (Root root : listRootsResult.getRoots()) {
                    // while unlikely, the account can be in the Root
                    if (sourceParentId == null) {
                        if (isAccountInParent(orgClient, root.getId(), accountId)) {
                            sourceParentId = root.getId();
                        }
                    }
                    getOrganizationalUnits(orgClient, organizationalUnits, root.getId());
                }

                listRootsRequest.setNextToken(listRootsResult.getNextToken());
            } while (listRootsResult.getNextToken() != null);

            // given the list of all organizational units, find the account and the ID of the destination OU
            for (OrganizationalUnit ou : organizationalUnits) {
                if (sourceParentId == null) {
                    if (isAccountInParent(orgClient, ou.getId(), accountId)) {
                        sourceParentId = ou.getId();
                    }
                }
                if (destinationParentId == null) {
                    if (ou.getName().equals(getDestinationParentName())) {
                        destinationParentId = ou.getId();
                    }
                }
                // once both bits of information are found we can stop looking
                if (sourceParentId != null && destinationParentId != null)
                    break;
            }

            if (sourceParentId == null) {
                String errMsg = "Unable to find the account in the Root or in any organizational unit";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
            if (destinationParentId == null) {
                String errMsg = "Unable to find the destination organizational unit by name: " + getDestinationParentName();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

            addResultProperty("accountId", accountId);
            addResultProperty("sourceParentId", sourceParentId);
            addResultProperty("destinationParentId", destinationParentId);
            addResultProperty("destinationParentName", getDestinationParentName());

            if (sourceParentId.equals(destinationParentId)) {
                logger.info(LOGTAG + "Account " + accountId + " is already in destination "
                        + destinationParentId + "(" + getDestinationParentName() + ")");
            }
            else {
                logger.info(LOGTAG + "Moving account " + accountId + " from " + sourceParentId
                        + " to " + destinationParentId + "(" + getDestinationParentName() + ")");

                MoveAccountRequest request = new MoveAccountRequest()
                        .withAccountId(accountId)
                        .withSourceParentId(sourceParentId)
                        .withDestinationParentId(destinationParentId);

                orgClient.moveAccount(request);
            }
        }
        catch (StepException e) {
            // message already logged, just rethrow
            throw e;
        }
        catch (Exception e) {
            String errMsg = "An error occurred moving the account. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
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
        String LOGTAG = getStepTag() + "[MoveAccountToNewDestination.simulate] ";
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
        String LOGTAG = getStepTag() + "[MoveAccountToNewDestination.fail] ";
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

        String LOGTAG = getStepTag() + "[MoveAccountToNewDestination.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private AWSOrganizationsClient buildOrganizationsClient() {
        BasicAWSCredentials basicCredentials = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(basicCredentials);
        return (AWSOrganizationsClient) AWSOrganizationsClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion(Regions.DEFAULT_REGION)
                .build();
    }

    private boolean isAccountInParent(AWSOrganizationsClient orgClient, String parentId, String accountId) {
        ListAccountsForParentRequest req = new ListAccountsForParentRequest()
                .withParentId(parentId);
        ListAccountsForParentResult res;
        do {
            res = orgClient.listAccountsForParent(req);
            for (Account account : res.getAccounts()) {
                if (account.getId().equals(accountId))
                    return true;
            }
            req.setNextToken(res.getNextToken());
        } while (res.getNextToken() != null);
        return false;
    }

    // recursively get OUs
    private void getOrganizationalUnits(AWSOrganizations orgClient, List<OrganizationalUnit> organizationalUnits, String parentId) {
        ListOrganizationalUnitsForParentRequest req = new ListOrganizationalUnitsForParentRequest()
                        .withParentId(parentId);
        ListOrganizationalUnitsForParentResult res;
        do {
            res = orgClient.listOrganizationalUnitsForParent(req);

            for (OrganizationalUnit ou : res.getOrganizationalUnits()) {
                organizationalUnits.add(ou);
                getOrganizationalUnits(orgClient, organizationalUnits, ou.getId());
            }

            req.setNextToken(res.getNextToken());
        } while (res.getNextToken() != null);
    }

    private void setAccessKeyId(String accessKeyId) throws StepException {
        if (accessKeyId == null) {
            String errMsg = "accessKeyId property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        this.accessKeyId = accessKeyId;
    }

    private String getAccessKeyId() {
        return this.accessKeyId;
    }

    private void setSecretKey(String secretKey) throws StepException {
        if (secretKey == null) {
            String errMsg = "secretKey property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        this.secretKey = secretKey;
    }

    private String getSecretKey() {
        return this.secretKey;
    }

    private void setDestinationParentName(String destinationParentName) throws StepException {
        if (destinationParentName == null) {
            String errMsg = "destinationParentId property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        this.destinationParentName = destinationParentName;
    }

    private String getDestinationParentName() {
        return this.destinationParentName;
    }
}
