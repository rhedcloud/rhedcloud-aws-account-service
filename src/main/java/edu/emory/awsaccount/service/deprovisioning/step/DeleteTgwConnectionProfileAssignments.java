package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayConnectionProfileAssignmentQuerySpecification;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Delete TransitGatewayConnectionProfileAssignment records for all TGW VPCs associated with an account.
 */
public class DeleteTgwConnectionProfileAssignments extends AbstractStep implements Step {

    private ProducerPool m_networkOpsServiceProducerPool = null;
    private int m_requestTimeoutIntervalInMillis = 600000;

    public void init(String provisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {

        super.init(provisioningId, props, aConfig, adp);

        String LOGTAG = getStepTag() + "[DeleteTgwConnectionProfileAssignments.init] ";

        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("NetworkOpsServiceProducerPool");
            setNetworkOpsServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            addResultProperty("errorMessage", errMsg);
            throw new StepException(errMsg);
        }

        // Get custom step properties.
        logger.info(LOGTAG + "Getting custom step properties...");

        String requestTimeoutInterval = getProperties().getProperty("requestTimeoutIntervalInMillis", "600000");
        int requestTimeoutIntervalInMillis = Integer.parseInt(requestTimeoutInterval);
        setRequestTimeoutIntervalInMillis(requestTimeoutIntervalInMillis);
        logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " + getRequestTimeoutIntervalInMillis());

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteTgwConnectionProfileAssignments.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);


        // Get the list of VPCs from a previous step.
        String vpcIds = getStepPropertyValue("LIST_VPC_IDS", "tgwVpcIds");

        // If there are no VPCs there is nothing to do and the step is complete.
        if (vpcIds.equals(PROPERTY_VALUE_NOT_AVAILABLE) || vpcIds.equals("none")) {
            logger.info(LOGTAG + "There are no TGW VPCs.");
            // Update the step.
            update(COMPLETED_STATUS, SUCCESS_RESULT);

            // Log completion time.
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Step run completed in " + time + "ms.");

            // Return the properties.
            return getResultProperties();
        }

        // Get the VPCs as a list.
        List<String> vpcList = Arrays.asList(vpcIds.split("\\s*,\\s*"));
        logger.info(LOGTAG + "There are " + vpcList.size() + " TGW VPCs.");

        // If there are no VPCs in the list there is nothing to do and the step is complete.
        if (vpcList.size() == 0) {
            logger.info(LOGTAG + "There are no TGW VPCs.");
            // Update the step.
            update(COMPLETED_STATUS, SUCCESS_RESULT);

            // Log completion time.
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Step run completed in " + time + "ms.");

            // Return the properties.
            return getResultProperties();
        }

        // For each VPC, query for a VPN connection profile assignment.
        List<TransitGatewayConnectionProfileAssignment> tgwConnectionProfileAssignments = new ArrayList<>();
        for (String vpcId : vpcList) {
            TransitGatewayConnectionProfileAssignment pa = queryForTgwConnectionProfileAssignment(vpcId);
            if (pa != null) {
                tgwConnectionProfileAssignments.add(pa);
            }
        }
        addResultProperty("tgwConnectionProfileAssignmentsCount", Integer.toString(tgwConnectionProfileAssignments.size()));

        // If there are no aJssignments to delete, there is nothing to do and the step is complete.
        if (tgwConnectionProfileAssignments.size() == 0) {
            logger.info(LOGTAG + "There are no TransitGatewayConnectionProfileAssignment records to delete. There is nothing to do.");
            // Update the step.
            update(COMPLETED_STATUS, SUCCESS_RESULT);

            // Log completion time.
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Step run completed in " + time + "ms.");

            // Return the properties.
            return getResultProperties();
        }

        // Delete each assignment
        int assignmentsDeleted = 0;
        for (TransitGatewayConnectionProfileAssignment assignment : tgwConnectionProfileAssignments) {
            // Get a request service from the pool and set the timeout interval.
            RequestService rs;
            try {
                PointToPointProducer p2p = (PointToPointProducer) getNetworkOpsServiceProducerPool().getExclusiveProducer();
                p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
                rs = p2p;
            } catch (JMSException e) {
                String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            try {
                long elapsedStartTime = System.currentTimeMillis();
                assignment.delete("Delete", rs);
                long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignment.Delete for TransitGatewayConnectionProfileId "
                        + assignment.getTransitGatewayConnectionProfileId() + " in " + elapsedTime + " ms.");
                assignmentsDeleted++;
            }
            catch (EnterpriseObjectDeleteException e) {
                String errMsg = "An error occurred deleting the TransitGatewayConnectionProfileAssignment object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
            finally {
                getNetworkOpsServiceProducerPool().releaseProducer((MessageProducer) rs);
            }
        }

        addResultProperty("assignmentsDeleted", Integer.toString(assignmentsDeleted));

        // The step is done. Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteTgwConnectionProfileAssignments.simulate] ";
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
        String LOGTAG = getStepTag() + "[DeleteTgwConnectionProfileAssignments.fail] ";
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
        String LOGTAG = getStepTag() + "[DeleteTgwConnectionProfileAssignments.rollback] ";

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private TransitGatewayConnectionProfileAssignment queryForTgwConnectionProfileAssignment(String vpcId) throws StepException {

        String LOGTAG = getStepTag() + "[DeleteTgwConnectionProfileAssignments.queryForTgwConnectionProfileAssignment] ";

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
            throw new StepException(errMsg, e);
        }

        // Set the values of the query spec.
        try {
            querySpec.setOwnerId(vpcId);
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Log the state of the object.
        try {
            logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignmentQuerySpecification is: " + querySpec.toXmlString());
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            PointToPointProducer p2p = (PointToPointProducer) getNetworkOpsServiceProducerPool().getExclusiveProducer();
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
            List<TransitGatewayConnectionProfileAssignment> results = assignment.query(querySpec, rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignment.Query for ownerId " + vpcId + "in " + elapsedTime + "ms."
                    + " There are " + results.size() + " result(s).");

            if (results.size() == 1) {
                return results.get(0);
            }
            else {
                String errMsg = "Invalid number of results returned from TransitGatewayConnectionProfileAssignment.Query-Request. "
                        + results.size() + " results returned. Expected exactly 1.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying for the VpnConnectionProfileAssignment object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getNetworkOpsServiceProducerPool().releaseProducer((MessageProducer) rs);
        }
    }


    private void setNetworkOpsServiceProducerPool(ProducerPool pool) {
        m_networkOpsServiceProducerPool = pool;
    }

    private ProducerPool getNetworkOpsServiceProducerPool() {
        return m_networkOpsServiceProducerPool;
    }

    private void setRequestTimeoutIntervalInMillis(int time) {
        m_requestTimeoutIntervalInMillis = time;
    }

    private int getRequestTimeoutIntervalInMillis() {
        return m_requestTimeoutIntervalInMillis;
    }
}
