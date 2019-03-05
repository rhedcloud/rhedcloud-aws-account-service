/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.jms.JMSException;

import org.apache.log4j.Logger;
import org.openeai.afa.ScheduledCommand;
import org.openeai.afa.ScheduledCommandException;
import org.openeai.afa.ScheduledCommandImpl;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.provider.ProviderException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloud;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudQuerySpecification;
import com.google.common.collect.Sets;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;

import edu.emory.moa.jmsobjects.network.v1_0.VpnConnection;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionQuerySpecification;

/**
 * This command interrogate
 * 
 * @author George Wang (gwang28@emory.edu)
 * @version 1.0 - 4 July 2018
 * 
 */
public class UnusedVpnConnectionReportScheduledCommand extends ScheduledCommandImpl implements ScheduledCommand {
    private String LOGTAG = "[UnusedVpnConnectionReportScheduledCommand] ";
    protected static final Logger logger = Logger.getLogger(UnusedVpnConnectionReportScheduledCommand.class);
    protected boolean m_verbose = false;
    protected static int requestTimeoutIntervalMilli = -1;

    protected ProducerPool networkOpsServiceRequestProducerPool;
    protected ProducerPool serviceNowServiceRequestProducerPool;
    protected ProducerPool awsAccountServiceRequestProducerPoolProd;
    protected ProducerPool awsAccountServiceRequestProducerPoolStage;
    protected ProducerPool awsAccountServiceRequestProducerPoolTest;

    protected String elasticIpRequestUserNetID = "webservice_admin_integration";
    protected String incidentRequisitionCallerId = "webservice_admin_integration";
    private List<String> manualVpcs = new ArrayList<>();
    private boolean excludeManualVpcs = true;

    public UnusedVpnConnectionReportScheduledCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
        logger.info(LOGTAG + " Initializing ...");
        PropertyConfig pConfig = new PropertyConfig();
        try {
            networkOpsServiceRequestProducerPool = (ProducerPool) getAppConfig().getObject("NetworkOpsServiceRequest");
            serviceNowServiceRequestProducerPool = (ProducerPool) getAppConfig().getObject("ServiceNowServiceRequest");
            awsAccountServiceRequestProducerPoolProd = (ProducerPool) getAppConfig().getObject("AwsAccountServiceRequestProd");
            awsAccountServiceRequestProducerPoolStage = (ProducerPool) getAppConfig().getObject("AwsAccountServiceRequestStage");
            awsAccountServiceRequestProducerPoolTest = (ProducerPool) getAppConfig().getObject("AwsAccountServiceRequestTest");
            pConfig = (PropertyConfig) getAppConfig().getObject("GeneralProperties");
            Properties props = pConfig.getProperties();
            setProperties(props);

            String manualVpcsStr = props.getProperty("manualVpcs");
            if (manualVpcsStr != null)
                manualVpcs = Arrays.asList(manualVpcsStr.split(","));
            Boolean.valueOf(props.getProperty("excludeManualVpcs", "true"));
            logger.info(LOGTAG + "manualVpcs.size=" + manualVpcs.size());

        } catch (Throwable t) {
            logger.error(LOGTAG, t);
            throw new InstantiationException(t.getMessage());
        }

        // Get the verbose property.
        String verbose = getProperties().getProperty("verbose", "false");
        setVerbose(Boolean.getBoolean(verbose));
        logger.info(LOGTAG + "property verbose: " + getVerbose());

        logger.info(LOGTAG + " Initialization complete.");
    }

    protected MessageProducer getRequestServiceMessageProducer(ProducerPool producerPool) {
        MessageProducer producer = null;
        try {
            producer = producerPool.getExclusiveProducer();
            if (producer instanceof PointToPointProducer) {
                PointToPointProducer p2pp = (PointToPointProducer) producer;
                if (requestTimeoutIntervalMilli != -1) {
                    p2pp.setRequestTimeoutInterval(requestTimeoutIntervalMilli);
                    if (getVerbose())
                        logger.info(LOGTAG + "p2pProducer.setRequestTimeoutInterval=" + requestTimeoutIntervalMilli);
                }
            }
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service. The " + "exception is: " + jmse.getMessage();
            logger.fatal(LOGTAG + errMsg, jmse);
            throw new java.lang.UnsupportedOperationException(errMsg, jmse);
        }
        return producer;
    }
    protected List<VpnConnectionProfileAssignment> queryVpnConnectionProfileAssignmentFromNetworkOpsService()
            throws EnterpriseConfigurationObjectException, EnterpriseObjectQueryException, EnterpriseFieldException {
        VpnConnectionProfileAssignment virtualPrivateCloud = (VpnConnectionProfileAssignment) getAppConfig()
                .getObjectByType(VpnConnectionProfileAssignment.class.getName());
        VpnConnectionProfileAssignmentQuerySpecification virtualPrivateCloudQuerySpecification = (VpnConnectionProfileAssignmentQuerySpecification) getAppConfig()
                .getObjectByType(VpnConnectionProfileAssignmentQuerySpecification.class.getName());
        MessageProducer networkOpsProducer = getRequestServiceMessageProducer(networkOpsServiceRequestProducerPool);
        List<VpnConnectionProfileAssignment> virtualPrivateClouds = new ArrayList<>();
        try {
            virtualPrivateClouds = virtualPrivateCloud.query(virtualPrivateCloudQuerySpecification, (RequestService) networkOpsProducer);
        } finally {
            networkOpsServiceRequestProducerPool.releaseProducer(networkOpsProducer);
        }
        return virtualPrivateClouds;
    }

    protected VpnConnectionProfile queryVpnConnectionProfileFromNetworkOpsService(String vpnConnectionProfileId)
            throws EnterpriseConfigurationObjectException, EnterpriseObjectQueryException, EnterpriseFieldException {
        VpnConnectionProfile vpnConnectionProfile = (VpnConnectionProfile) getAppConfig()
                .getObjectByType(VpnConnectionProfile.class.getName());
        VpnConnectionProfileQuerySpecification vpnConnectionProfileQuerySpecification = (VpnConnectionProfileQuerySpecification) getAppConfig()
                .getObjectByType(VpnConnectionProfileQuerySpecification.class.getName());
        vpnConnectionProfileQuerySpecification.setVpnConnectionProfileId(vpnConnectionProfileId);
        MessageProducer networkOpsProducer = getRequestServiceMessageProducer(networkOpsServiceRequestProducerPool);
        List<VpnConnectionProfile> vpnConnectionProfiles = new ArrayList<>();
        try {
            vpnConnectionProfiles = vpnConnectionProfile.query(vpnConnectionProfileQuerySpecification, (RequestService) networkOpsProducer);
        } finally {
            networkOpsServiceRequestProducerPool.releaseProducer(networkOpsProducer);
        }
        return vpnConnectionProfiles.get(0);
    }
    protected List<VirtualPrivateCloud> vpcsIn(ProducerPool producerPool)
            throws EnterpriseConfigurationObjectException, EnterpriseObjectQueryException, EnterpriseFieldException {
        VirtualPrivateCloud virtualPrivateCloud = (VirtualPrivateCloud) getAppConfig().getObjectByType(VirtualPrivateCloud.class.getName());
        VirtualPrivateCloudQuerySpecification virtualPrivateCloudQuerySpecification = (VirtualPrivateCloudQuerySpecification) getAppConfig()
                .getObjectByType(VirtualPrivateCloudQuerySpecification.class.getName());
        MessageProducer awsAccountProducer = getRequestServiceMessageProducer(producerPool);
        List<VirtualPrivateCloud> virtualPrivateClouds = new ArrayList<>();
        try {
            virtualPrivateClouds = virtualPrivateCloud.query(virtualPrivateCloudQuerySpecification, (RequestService) awsAccountProducer);
        } finally {
            producerPool.releaseProducer(awsAccountProducer);
        }
        return virtualPrivateClouds;
    }
    protected boolean vpcExistsIn(String ownerId, RequestService awsAccountProducer)
            throws EnterpriseConfigurationObjectException, EnterpriseObjectQueryException, EnterpriseFieldException {
        VirtualPrivateCloud virtualPrivateCloud = (VirtualPrivateCloud) getAppConfig().getObjectByType(VirtualPrivateCloud.class.getName());
        VirtualPrivateCloudQuerySpecification virtualPrivateCloudQuerySpecification = (VirtualPrivateCloudQuerySpecification) getAppConfig()
                .getObjectByType(VirtualPrivateCloudQuerySpecification.class.getName());
        virtualPrivateCloudQuerySpecification.setVpcId(ownerId);
        List<VirtualPrivateCloud> virtualPrivateClouds = virtualPrivateCloud.query(virtualPrivateCloudQuerySpecification,
                awsAccountProducer);
        return virtualPrivateClouds.size() > 0;
    }

    @Override
    public int execute() throws ScheduledCommandException {
        try {
            List<VpnConnectionProfileAssignment> vpnConnectionProfileAssignments = queryVpnConnectionProfileAssignmentFromNetworkOpsService();
            logger.info("vpnConnectionProfileAssignments.size=" + vpnConnectionProfileAssignments.size());
            List<VirtualPrivateCloud> vpcsProd = vpcsIn(awsAccountServiceRequestProducerPoolProd);
            logger.info("vpcsProd.size=" + vpcsProd.size());
            List<VirtualPrivateCloud> vpcsStage = vpcsIn(awsAccountServiceRequestProducerPoolStage);
            logger.info("vpcsStage.size=" + vpcsStage.size());
            List<VirtualPrivateCloud> vpcsTest = vpcsIn(awsAccountServiceRequestProducerPoolTest);
            logger.info("vpcsTest.size=" + vpcsTest.size());

            List<String> vpcIdsProd = vpcsProd.stream().map(v -> v.getVpcId()).collect(Collectors.toList());
            List<String> vpcIdsStage = vpcsStage.stream().map(v -> v.getVpcId()).collect(Collectors.toList());
            List<String> vpcIdsTest = vpcsTest.stream().map(v -> v.getVpcId()).collect(Collectors.toList());
            Collections.sort(vpcIdsProd);
            Collections.sort(vpcIdsStage);
            Collections.sort(vpcIdsTest);
            logger.info("vpcIdsProd=" + vpcIdsProd);
            logger.info("vpcIdsStage=" + vpcIdsStage);
            logger.info("vpcIdsTest=" + vpcIdsTest);
            Set<String> vpcIds = new TreeSet<>();
            vpcIds.addAll(vpcIdsProd);
            vpcIds.addAll(vpcIdsStage);
            vpcIds.addAll(vpcIdsTest);
            logger.info("vpcIds.size=" + vpcIds.size());
            logger.info("vpcIds=" + vpcIds);

            Set<String> ownerIds = vpnConnectionProfileAssignments.stream().map(v -> v.getOwnerId()).collect(Collectors.toSet());
            logger.info("ownerIdsUnique.size=" + ownerIds.size());
            Set<String> vpcIdsNotInAssingment = vpcIds.stream().filter(id -> !ownerIds.contains(id))
                    .collect(Collectors.toCollection(TreeSet::new));
            logger.info("vpcIdsNotInAssingment.size=" + vpcIdsNotInAssingment.size());
            logger.info("vpcIdsNotInAssingment=" + vpcIdsNotInAssingment);

            List<VpnConnectionProfileAssignment> vpnConnectionProfileAssignmentsNoVpc = vpnConnectionProfileAssignments.stream()
                    .filter(v -> !vpcIds.contains(v.getOwnerId())).collect(Collectors.toList());
            logger.info("vpnConnectionProfileAssignmentsNoVpc.size=" + vpnConnectionProfileAssignmentsNoVpc.size());

            List<String> vpnConnectionProfileAssignmentsNoVpcOwnerIds = vpnConnectionProfileAssignmentsNoVpc.stream()
                    .map(v -> v.getOwnerId()).collect(Collectors.toList());
            Collections.sort(vpnConnectionProfileAssignmentsNoVpcOwnerIds);
            logger.info("vpnConnectionProfileAssignmentsNoVpcOwnerIds=" + vpnConnectionProfileAssignmentsNoVpcOwnerIds);
            vpnConnectionProfileAssignments.forEach(v -> {
                String currentVpc = v.getOwnerId();
                if (vpnConnectionProfileAssignmentsNoVpcOwnerIds.contains(currentVpc)
                        && (!excludeManualVpcs || (excludeManualVpcs && !manualVpcs.contains(currentVpc)))) {
                    try {
                        VpnConnectionProfile vpnConnectionProfile = queryVpnConnectionProfileFromNetworkOpsService(
                                v.getVpnConnectionProfileId());
                        String shortDescription = "This VpnConnection no longer has corresponding VPC.  Please deprovision this VpnConnection: ownerId= "
                                + v.getOwnerId() + ",vpnConnectionProfileId=" + vpnConnectionProfile.getVpnConnectionProfileId()
                                + ",vpcNetwork=" + vpnConnectionProfile.getVpcNetwork();
                        serviceNowIncidentGenerate(shortDescription,
                                "sendFrom: " + UnusedVpnConnectionReportScheduledCommand.class.getName());
                    } catch (EnterpriseConfigurationObjectException | EnterpriseObjectQueryException | EnterpriseFieldException
                            | ProviderException e) {
                        logger.error(LOGTAG, e);
                    }
                }
            });

        } catch (EnterpriseConfigurationObjectException | EnterpriseObjectQueryException | EnterpriseFieldException e) {
            logger.error(LOGTAG, e);
        }
        return 0;
    }

    protected Incident serviceNowIncidentGenerate(String shorDescription, String description)
            throws org.openeai.jms.consumer.commands.provider.ProviderException {
        List<Incident> generatedIncidents = null;
        try {
            Incident incident = (Incident) getAppConfig().getObjectByType(Incident.class.getName());
            IncidentRequisition incidentRequisition = (IncidentRequisition) getAppConfig()
                    .getObjectByType(IncidentRequisition.class.getName());
            incidentRequisition.setShortDescription(shorDescription);
            incidentRequisition.setDescription(description);
            incidentRequisition.setUrgency("3");
            incidentRequisition.setImpact("3");
            incidentRequisition.setBusinessService("Application Management");
            incidentRequisition.setCategory("Configuration");
            incidentRequisition.setSubCategory("Add");
            incidentRequisition.setRecordType("Service Request");
            incidentRequisition.setContactType("Integration");
            incidentRequisition.setCallerId(incidentRequisitionCallerId);
            incidentRequisition.setCmdbCi("Emory AWS Service");
            incidentRequisition.setAssignmentGroup("LITS: Messaging - Tier 3");
            logger.info(LOGTAG + "incident.generate:incidentRequisition=" + incidentRequisition.toXmlString());
            MessageProducer serviceNowProducer = getRequestServiceMessageProducer(serviceNowServiceRequestProducerPool);
            try {
                generatedIncidents = incident.generate(incidentRequisition, (RequestService) serviceNowProducer);
            } finally {
                serviceNowServiceRequestProducerPool.releaseProducer(serviceNowProducer);
            }
        } catch (Throwable e1) {
            logger.error(LOGTAG, e1);
            throw new org.openeai.jms.consumer.commands.provider.ProviderException("ServiceNowIncident.Generate error:" + e1.getMessage());
        }
        return generatedIncidents.get(0);
    }

    /**
     * @param boolean,
     *            the verbose parameter
     *            <P>
     *            Set a parameter to toggle verbose logging.
     */
    protected void setVerbose(boolean b) {
        m_verbose = b;
    }

    /**
     * @return boolean, the verbose parameter
     *         <P>
     *         Gets the value of the verbose logging parameter.
     */
    protected boolean getVerbose() {
        return m_verbose;
    }
}
