package edu.emory.awsaccount.service.provider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloud;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudQuerySpecification;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
import com.amazonaws.services.ec2.model.DescribeTransitGatewayAttachmentsRequest;
import com.amazonaws.services.ec2.model.DescribeTransitGatewayAttachmentsResult;
import com.amazonaws.services.ec2.model.DescribeTransitGatewaysRequest;
import com.amazonaws.services.ec2.model.DescribeTransitGatewaysResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.SearchTransitGatewayRoutesRequest;
import com.amazonaws.services.ec2.model.SearchTransitGatewayRoutesResult;
import com.amazonaws.services.ec2.model.TransitGatewayAttachment;
import com.amazonaws.services.ec2.model.TransitGatewayRoute;
import edu.emory.awsaccount.service.AwsClientBuilderHelper;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayConnectionProfileAssignment;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayStatus;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayConnectionProfileAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayConnectionProfileQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayProfile;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayStatusQuerySpecification;
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;

import javax.jms.JMSException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AccountTransitGatewayStatusProvider extends OpenEaiObject implements TransitGatewayStatusProvider {
    private AppConfig appConfig;
    private ProducerPool awsAccountServiceProducerPool;
    private ProducerPool networkOpsServiceProducerPool;

    private boolean verbose;

    private String accessKeyId = null;
    private String secretKey = null;
    private String roleArnPattern = null;
    private int roleAssumptionDurationSeconds = 0;

    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        final String LOGTAG = "[AccountTransitGatewayStatusProvider.init] ";
        logger.info(LOGTAG + "Initializing...");
        setAppConfig(aConfig);

        // Get the provider properties
        try {
            PropertyConfig pConfig = (PropertyConfig) getAppConfig().getObject("TransitGatewayStatusProviderProperties");
            setProperties(pConfig.getProperties());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving a PropertyConfig object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // careful - this will report the secretKey
        //logger.info(LOGTAG + getProperties().toString());

        // Set the properties for the provider
        setVerbose(Boolean.parseBoolean(getProperties().getProperty("verbose", "false")));
        logger.info(LOGTAG + "verbose property is: " + getVerbose());

        try {
            ProducerPool pool = (ProducerPool) getAppConfig().getObject("NetworkOpsServiceProducerPool");
            setNetworkOpsServiceProducerPool(pool);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving the NetworkOpsServiceProducerPool object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }

        try {
            ProducerPool pool = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(pool);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving the AwsAccountServiceProducerPool object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }

        // Get the AWS credentials the provider will use
        String accessKeyId = getProperties().getProperty("accessKeyId");
        if (accessKeyId == null || accessKeyId.equals("")) {
            String errMsg = "No accessKeyId property specified. Can't continue.";
            throw new ProviderException(errMsg);
        }
        setAccessKeyId(accessKeyId);

        String secretKey = getProperties().getProperty("secretKey");
        if (secretKey == null || secretKey.equals("")) {
            String errMsg = "No secretKey property specified. Can't continue.";
            throw new ProviderException(errMsg);
        }
        setSecretKey(secretKey);

        String roleArnPattern = getProperties().getProperty("roleArnPattern");
        if (roleArnPattern == null || roleArnPattern.equals("")) {
            String errMsg = "No roleArnPattern property specified. Can't continue.";
            throw new ProviderException(errMsg);
        }
        setRoleArnPattern(roleArnPattern);

        String sRoleAssumptionDurationSeconds = getProperties().getProperty("roleAssumptionDurationSeconds");
        if (sRoleAssumptionDurationSeconds == null || sRoleAssumptionDurationSeconds.equals("")) {
            String errMsg = "No roleAssumptionDurationSeconds property specified. Can't continue.";
            throw new ProviderException(errMsg);
        }
        setRoleAssumptionDurationSeconds(Integer.parseInt(sRoleAssumptionDurationSeconds));

        logger.info(LOGTAG + "Initialization complete.");
    }

    /*
     * Certain status fields can be set if unexpected data is found while collecting TGW information:
     *   MissingConnectionProfileAssignment - found a registered TGW VPC but no TransitGatewayConnectionProfileAssignment
     *   MissingConnectionProfile - found a registered TGW VPC and an assignment but no TransitGatewayConnectionProfile
     *   InvalidTransitGatewayProfile - a TransitGateway records returned by NetworkOps has an invalid number of profiles
     *   MissingTransitGateway - found a registered TGW VPC but can not find the Transit Gateway in AWS
     *   MissingTransitGatewayAttachment - found a registered TGW VPC but can not find a Transit Gateway Attachment in AWS
     *   WrongTransitGatewayAttachment - found a registered TGW VPC but the Transit Gateway Attachment in AWS
     *                                   has a different TransitGatewayId from the TransitGatewayConnectionProfile
     *
     * From the VPC account, check to see if the TGW it is supposed to be attached to is available
     *       Green if return value is available and Red if return value is not available
     *   WrongTransitGateway - the TransitGatewayConnectionProfileAssignment and TransitGatewayConnectionProfile
     *                         indicate a specific TransitGatewayId but it can not be found in the list of
     *                         configured TransitGateway records returned by NetworkOps
     *   TgwStatus - the status (or state) of the Transit Gateway as returned by AWS
     *
     * From the VPC account, check to see if the Attachment state is available
     *   TgwAttachmentId - the identifier of the Transit Gateway Attachment as returned by AWS
     *   TgwAttachmentStatus - the status (or state) of the Transit Gateway Attachment as returned by AWS
     *
     * From the VPC account, check to see if the Association Status is associated
     *   MissingTgwAttachmentAssociation - the Transit Gateway Attachment returned by AWS is missing the association
     *   TgwAttachmentAssociationStatus - the status of the Transit Gateway Attachment association as returned by AWS
     *
     * From the TGW account, check to see if the VPC TGW attachment ID is present in the association route Table
     *   TgwAttachmentAssociationCorrect - the route table ID of the association matches the ID in the TransitGatewayConnectionProfile
     *                                     if it doesn't match, the reason for the mismatch
     *
     * From the TGW account, check to see if the VPC TGW attachment ID is present in the correct propagation route tables
     *   TgwAttachmentPropagationCorrect - the route table ID of the propagations matches the ID in the TransitGatewayConnectionProfile
     *                                     if it doesn't match, the reason for the mismatch
     */
    @Override
    public List<TransitGatewayStatus> query(TransitGatewayStatusQuerySpecification tgwQuerySpec) throws ProviderException {
        final String LOGTAG = "[AccountTransitGatewayStatusProvider.query] ";
        logger.info(LOGTAG + "Querying for TransitGatewayStatus with AccountId " + tgwQuerySpec.getAccountId() + " and VpcId " + tgwQuerySpec.getVpcId());


        List<VirtualPrivateCloud> virtualPrivateClouds = queryForTgwVirtualPrivateClouds(LOGTAG, tgwQuerySpec.getAccountId(), tgwQuerySpec.getVpcId());
        List<TransitGatewayConnectionProfileAssignment> tgwConnectionProfileAssignments = queryForTgwConnectionProfileAssignment(LOGTAG, virtualPrivateClouds);
        List<TransitGatewayConnectionProfile> tgwConnectionProfiles = queryForTgwConnectionProfiles(LOGTAG);
        List<edu.emory.moa.jmsobjects.network.v1_0.TransitGateway> transitGatewayMoas = queryForTransitGateways(LOGTAG);

        List<TransitGatewayStatus> results = new ArrayList<>();
        try {
            for (VirtualPrivateCloud vpc : virtualPrivateClouds) {
                TransitGatewayConnectionProfileAssignment profileAssignment = null;
                String transitGatewayId = null;  // the VPC is supposed to be attached to this TGW
                foundConnectionProfile:
                for (TransitGatewayConnectionProfileAssignment assignment : tgwConnectionProfileAssignments) {
                    if (assignment.getOwnerId().equals(vpc.getVpcId())) {
                        profileAssignment = assignment;

                        for (TransitGatewayConnectionProfile profile : tgwConnectionProfiles) {
                            if (profile.getTransitGatewayConnectionProfileId().equals(assignment.getTransitGatewayConnectionProfileId())) {
                                transitGatewayId = profile.getTransitGatewayId();
                                break foundConnectionProfile;
                            }
                        }
                    }
                }

                // status report for this TGW VPC
                TransitGatewayStatus transitGatewayStatus = (TransitGatewayStatus) getAppConfig().getObjectByType(TransitGatewayStatus.class.getName());
                results.add(transitGatewayStatus);

                transitGatewayStatus.setAccountId(vpc.getAccountId());
                transitGatewayStatus.setRegion(vpc.getRegion());
                transitGatewayStatus.setVpcId(vpc.getVpcId());
                transitGatewayStatus.setTransitGatewayId(transitGatewayId);

                if (profileAssignment == null) {
                    /* found a registered TGW VPC but no TransitGatewayConnectionProfileAssignment */
                    transitGatewayStatus.setMissingConnectionProfileAssignment("true");
                    continue;
                }
                if (transitGatewayId == null) {
                    /* found a registered TGW VPC and an assignment but no TransitGatewayConnectionProfile */
                    transitGatewayStatus.setMissingConnectionProfile("true");
                    continue;
                }

                edu.emory.moa.jmsobjects.network.v1_0.TransitGateway transitGatewayMoa = null;
                for (edu.emory.moa.jmsobjects.network.v1_0.TransitGateway tgwMoa : transitGatewayMoas) {
                    if (tgwMoa.getTransitGatewayId().equals(transitGatewayId)) {
                        transitGatewayMoa = tgwMoa;
                        break;
                    }
                }
                if (transitGatewayMoa == null) {
                    transitGatewayStatus.setWrongTransitGateway("true");
                    continue;
                }
                // see the note in DetermineVpcConnectionMethod about the validation on the number of TransitGatewayProfile's
                if (transitGatewayMoa.getTransitGatewayProfile().size() != 1) {
                    transitGatewayStatus.setInvalidTransitGatewayProfile("true");
                    continue;
                }
                TransitGatewayProfile tgwProfile = (TransitGatewayProfile) transitGatewayMoa.getTransitGatewayProfile().get(0);

                AmazonEC2Client memberEc2Client = AwsClientBuilderHelper.buildAmazonEC2Client(vpc.getAccountId(), vpc.getRegion(),
                        getAccessKeyId(), getSecretKey(), getRoleArnPattern(), getRoleAssumptionDurationSeconds());

                DescribeTransitGatewaysRequest describeTransitGatewaysRequest = new DescribeTransitGatewaysRequest()
                        .withTransitGatewayIds(transitGatewayId);
                DescribeTransitGatewaysResult describeTransitGatewaysResult;
                com.amazonaws.services.ec2.model.TransitGateway awsTransitGateway = null;
                foundTransitGateway:
                do {
                    describeTransitGatewaysResult = memberEc2Client.describeTransitGateways(describeTransitGatewaysRequest);
                    for (com.amazonaws.services.ec2.model.TransitGateway tgw : describeTransitGatewaysResult.getTransitGateways()) {
                        awsTransitGateway = tgw;
                        break foundTransitGateway;
                    }
                    describeTransitGatewaysRequest.setNextToken(describeTransitGatewaysResult.getNextToken());
                } while (describeTransitGatewaysResult.getNextToken() != null);

                if (awsTransitGateway == null) {
                    /* found a registered TGW VPC but no Transit Gateway in AWS */
                    transitGatewayStatus.setMissingTransitGateway("true");
                    continue;
                }

                DescribeTransitGatewayAttachmentsRequest describeTransitGatewayAttachmentsRequest
                        = new DescribeTransitGatewayAttachmentsRequest()
                                .withFilters(new Filter("resource-type").withValues("vpc"),
                                             new Filter("resource-id").withValues(vpc.getVpcId()));
                DescribeTransitGatewayAttachmentsResult describeTransitGatewayAttachmentsResult;
                List<TransitGatewayAttachment> transitGatewayAttachments = new ArrayList<>();
                do {
                    describeTransitGatewayAttachmentsResult = memberEc2Client.describeTransitGatewayAttachments(describeTransitGatewayAttachmentsRequest);
                    transitGatewayAttachments.addAll(describeTransitGatewayAttachmentsResult.getTransitGatewayAttachments());
                    describeTransitGatewayAttachmentsRequest.setNextToken(describeTransitGatewayAttachmentsResult.getNextToken());
                } while (describeTransitGatewayAttachmentsResult.getNextToken() != null);

                if (transitGatewayAttachments.size() != 1) {
                    /* found a registered TGW VPC but no Transit Gateway Attachment in AWS */
                    transitGatewayStatus.setMissingTransitGatewayAttachment("true");
                    continue;
                }
                TransitGatewayAttachment transitGatewayAttachment = transitGatewayAttachments.get(0);
                if (!transitGatewayAttachment.getTransitGatewayId().equals(transitGatewayId)) {
                    /* found a registered TGW VPC but the Transit Gateway Attachment in AWS doesn't match the connection profile */
                    transitGatewayStatus.setWrongTransitGatewayAttachment("true");
                    continue;
                }
                // From the VPC account, check to see if the TGW it is supposed to be attached to is available
                transitGatewayStatus.setTgwStatus(awsTransitGateway.getState());
                // From the VPC account, check to see if the Attachment state is available
                transitGatewayStatus.setTgwAttachmentId(transitGatewayAttachment.getTransitGatewayAttachmentId());
                transitGatewayStatus.setTgwAttachmentStatus(transitGatewayAttachment.getState());

                // From the VPC account, check to see if the Association Status is associated
                if (transitGatewayAttachment.getAssociation() == null) {
                    transitGatewayStatus.setMissingTgwAttachmentAssociation("true");
                }
                else {
                    transitGatewayStatus.setTgwAttachmentAssociationStatus(transitGatewayAttachment.getAssociation().getState());
                }

                // From the TGW account, check to see if the VPC TGW attachment ID is present in the association route Table.
                // Green if returned TransitGatewayRouteTableId matches the ID from the profile.
                // Otherwise, give the reason it is not correct.
                if (tgwProfile.getAssociationRouteTableId() == null) {
                    transitGatewayStatus.setTgwAttachmentAssociationCorrect("Missing attachment association route table ID in profile");
                }
                else if (transitGatewayAttachment.getAssociation().getTransitGatewayRouteTableId() == null) {
                    transitGatewayStatus.setTgwAttachmentAssociationCorrect("Missing attachment association route table ID");
                }
                else if (!tgwProfile.getAssociationRouteTableId().equals(transitGatewayAttachment.getAssociation().getTransitGatewayRouteTableId())) {
                    transitGatewayStatus.setTgwAttachmentAssociationCorrect("Attachment association route table ID does not match profile");
                }
                else {
                    transitGatewayStatus.setTgwAttachmentAssociationCorrect("correct");
                }


                AmazonEC2Client tgwEc2Client = AwsClientBuilderHelper.buildAmazonEC2Client(transitGatewayAttachment.getTransitGatewayOwnerId(), vpc.getRegion(),
                        getAccessKeyId(), getSecretKey(), getRoleArnPattern(), getRoleAssumptionDurationSeconds());

                // From the TGW account, check to see if the VPC TGW attachment ID is present in the correct propagation route tables

                @SuppressWarnings("unchecked")
                List<String> propagationRouteTableIds = tgwProfile.getPropagationRouteTableId();
                String propagationCorrect = null;
                if (propagationRouteTableIds.size() == 0) {
                    propagationCorrect = "Missing attachment propagation route table IDs in profile";
                }
                else {
                    SearchTransitGatewayRoutesRequest searchTransitGatewayRoutesRequest = new SearchTransitGatewayRoutesRequest()
                            .withFilters(new Filter("attachment.transit-gateway-attachment-id").withValues(transitGatewayAttachment.getTransitGatewayAttachmentId()));
                    SearchTransitGatewayRoutesResult searchTransitGatewayRoutesResult;
                    for (String routeTableId : propagationRouteTableIds) {
                        searchTransitGatewayRoutesRequest.setTransitGatewayRouteTableId(routeTableId);
                        try {
                            searchTransitGatewayRoutesResult = tgwEc2Client.searchTransitGatewayRoutes(searchTransitGatewayRoutesRequest);
                            if (searchTransitGatewayRoutesResult.getRoutes().size() == 0) {
                                propagationCorrect = "Missing attachment propagation routes for route table ID " + routeTableId;
                            }
                            else {
                                for (TransitGatewayRoute route : searchTransitGatewayRoutesResult.getRoutes()) {
                                    if (!route.getState().equals("active")) {
                                        propagationCorrect = "Attachment propagation route is not active for route table ID " + routeTableId;
                                        break;
                                    }
                                }
                            }
                        }
                        catch (AmazonEC2Exception e) {
                            propagationCorrect = "Missing attachment propagation route table ID for route " + routeTableId;
                        }
                        if (propagationCorrect != null)
                            break;  // we're done as soon as a propagation route has an error
                    }
                }
                if (propagationCorrect == null)
                    propagationCorrect = "correct";
                transitGatewayStatus.setTgwAttachmentPropagationCorrect(propagationCorrect);
            }
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting objects from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the field values of the TransitGatewayStatus. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        catch (Exception e) {
            String errMsg = "An error occurred getting the status of the VPC. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        return results;
    }

    private List<VirtualPrivateCloud> queryForTgwVirtualPrivateClouds(String LOGTAG, String accountId, String vpcId) throws ProviderException {
        VirtualPrivateCloud virtualPrivateCloud;
        VirtualPrivateCloudQuerySpecification vpcQuerySpec;
        try {
            virtualPrivateCloud = (VirtualPrivateCloud) getAppConfig().getObjectByType(VirtualPrivateCloud.class.getName());
            vpcQuerySpec = (VirtualPrivateCloudQuerySpecification) getAppConfig().getObjectByType(VirtualPrivateCloudQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting objects from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        try {
            if (accountId != null)
                vpcQuerySpec.setAccountId(accountId);
            if (vpcId != null)
                vpcQuerySpec.setVpcId(vpcId);
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the VirtualPrivateCloudQuerySpecification. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Get a RequestService to use for this transaction.
        PointToPointProducer p2p;
        try {
            p2p = (PointToPointProducer) getAwsAccountServiceProducerPool().getExclusiveProducer();
            p2p.setRequestTimeoutInterval(1_000_000);
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<VirtualPrivateCloud> results = virtualPrivateCloud.query(vpcQuerySpec, p2p);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            List<VirtualPrivateCloud> tgwVpcs = results.stream()
                    .filter(r -> r.getVpcConnectionMethod().equals("TGW"))
                    .collect(Collectors.toList());
            logger.info(LOGTAG + "Queried for VPC metadata in " + elapsedTime + " ms."
                    + " Found " + tgwVpcs.size() + " TGW VPC(s) out of " + results.size() + " VPC(s).");
            return tgwVpcs;
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying for VPC metadata. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer(p2p);
        }
    }

    private List<TransitGatewayConnectionProfileAssignment> queryForTgwConnectionProfileAssignment(String LOGTAG, List<VirtualPrivateCloud> virtualPrivateClouds) throws ProviderException {
        // Get a configured objects from AppConfig
        TransitGatewayConnectionProfileAssignment assignment;
        TransitGatewayConnectionProfileAssignmentQuerySpecification querySpec;
        try {
            assignment = (TransitGatewayConnectionProfileAssignment) getAppConfig().getObjectByType(TransitGatewayConnectionProfileAssignment.class.getName());
            querySpec = (TransitGatewayConnectionProfileAssignmentQuerySpecification) getAppConfig().getObjectByType(TransitGatewayConnectionProfileAssignmentQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        PointToPointProducer p2p;
        try {
            p2p = (PointToPointProducer) getNetworkOpsServiceProducerPool().getExclusiveProducer();
            p2p.setRequestTimeoutInterval(1_000_000);
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        try {
            List<TransitGatewayConnectionProfileAssignment> tgwConnectionProfileAssignments = new ArrayList<>();

            for (VirtualPrivateCloud vpc : virtualPrivateClouds) {
                try {
                    querySpec.setOwnerId(vpc.getVpcId());
                    logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignmentQuerySpecification is: " + querySpec.toXmlString());

                    long elapsedStartTime = System.currentTimeMillis();
                    @SuppressWarnings("unchecked")
                    List<TransitGatewayConnectionProfileAssignment> results = assignment.query(querySpec, p2p);
                    long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                    logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignment.Query for ownerId " + vpc.getVpcId() + "in " + elapsedTime + "ms."
                            + " There are " + results.size() + " result(s).");

                    tgwConnectionProfileAssignments.addAll(results);
                }
                catch (EnterpriseObjectQueryException e) {
                    String errMsg = "An error occurred querying for the TransitGatewayConnectionProfileAssignment object. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new ProviderException(errMsg, e);
                }
                catch (EnterpriseFieldException e) {
                    String errMsg = "An error occurred setting the values of the TransitGatewayConnectionProfileAssignmentQuerySpecification object. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new ProviderException(errMsg, e);
                }
                catch (XmlEnterpriseObjectException e) {
                    String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new ProviderException(errMsg, e);
                }
            }

            return tgwConnectionProfileAssignments;
        }
        finally {
            getNetworkOpsServiceProducerPool().releaseProducer(p2p);
        }
    }

    private List<TransitGatewayConnectionProfile> queryForTgwConnectionProfiles(String LOGTAG) throws ProviderException {
        TransitGatewayConnectionProfile profile;
        TransitGatewayConnectionProfileQuerySpecification profileQuerySpec;
        try {
            profile = (TransitGatewayConnectionProfile) getAppConfig().getObjectByType(TransitGatewayConnectionProfile.class.getName());
            profileQuerySpec = (TransitGatewayConnectionProfileQuerySpecification) getAppConfig().getObjectByType(TransitGatewayConnectionProfileQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        PointToPointProducer p2p;
        try {
            p2p = (PointToPointProducer) getNetworkOpsServiceProducerPool().getExclusiveProducer();
            p2p.setRequestTimeoutInterval(1_000_000);
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<TransitGatewayConnectionProfile> profiles = profile.query(profileQuerySpec, p2p);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "TransitGatewayConnectionProfile.Query took " + elapsedTime + " ms. Returned " + profiles.size() + " results.");

            return profiles;
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying for the TransitGatewayConnectionProfile objects. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getNetworkOpsServiceProducerPool().releaseProducer(p2p);
        }
    }

    private List<edu.emory.moa.jmsobjects.network.v1_0.TransitGateway> queryForTransitGateways(String LOGTAG) throws ProviderException {
        edu.emory.moa.jmsobjects.network.v1_0.TransitGateway moaTransitGateway;
        TransitGatewayQuerySpecification tgwQuerySpec;
        try {
            moaTransitGateway = (edu.emory.moa.jmsobjects.network.v1_0.TransitGateway) getAppConfig().getObjectByType(edu.emory.moa.jmsobjects.network.v1_0.TransitGateway.class.getName());
            tgwQuerySpec = (TransitGatewayQuerySpecification) getAppConfig().getObjectByType(TransitGatewayQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        try {
            tgwQuerySpec.setEnvironment("DEV");  // TODO - from app config
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the TGW query spec. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        PointToPointProducer p2p;
        try {
            p2p = (PointToPointProducer) getNetworkOpsServiceProducerPool().getExclusiveProducer();
            p2p.setRequestTimeoutInterval(1_000_000);
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<edu.emory.moa.jmsobjects.network.v1_0.TransitGateway> transitGateways = moaTransitGateway.query(tgwQuerySpec, p2p);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "TransitGatewayConnectionProfile.Query took " + elapsedTime + " ms. Returned " + transitGateways.size() + " results.");

            return transitGateways;
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying for the TransitGateway objects. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getNetworkOpsServiceProducerPool().releaseProducer(p2p);
        }
    }

    private AppConfig getAppConfig() { return appConfig; }
    private void setAppConfig(AppConfig v) { this.appConfig = v; }
    private ProducerPool getAwsAccountServiceProducerPool() { return awsAccountServiceProducerPool; }
    private void setAwsAccountServiceProducerPool(ProducerPool v) { this.awsAccountServiceProducerPool = v; }
    private ProducerPool getNetworkOpsServiceProducerPool() { return networkOpsServiceProducerPool; }
    private void setNetworkOpsServiceProducerPool(ProducerPool v) { this.networkOpsServiceProducerPool = v; }

    private boolean getVerbose() { return verbose; }
    private void setVerbose(boolean v) { this.verbose = v; }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String v) { this.accessKeyId = v; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String v) { this.secretKey = v; }
    public String getRoleArnPattern() { return roleArnPattern; }
    public void setRoleArnPattern(String v) { this.roleArnPattern = v; }
    public int getRoleAssumptionDurationSeconds() { return roleAssumptionDurationSeconds; }
    public void setRoleAssumptionDurationSeconds(int v) { this.roleAssumptionDurationSeconds = v; }
}
