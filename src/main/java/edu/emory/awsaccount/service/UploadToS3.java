package edu.emory.awsaccount.service;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.apache.log4j.Logger;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;

public class UploadToS3 {
    String LOGTAG = "[UploadToS3] ";
    public final static String FILE_NAME_DELIMITER = ";";
    final static Logger LOG = Logger.getLogger(UploadToS3.class);
    private String bucketName = "edu.emory.awsbilling.accountmetadata";
    private String accessKeyId = "***REMOVED***";
    private String secretKey = "***REMOVED***";
    public UploadToS3(Properties properties) {
        if (properties != null) {
            accessKeyId = properties.getProperty("accessKeyId");
            secretKey = properties.getProperty("secretKey");
            bucketName = properties.getProperty("bucketName");
        }
    }
    public void execute(String keyName, String fileName) {
        try {
            BasicAWSCredentials awsCreds = new BasicAWSCredentials(accessKeyId, secretKey);
            AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_2)
                    .withCredentials(new AWSStaticCredentialsProvider(awsCreds)).build();
            File file = new File(fileName);
            LOG.info(LOGTAG + file.getAbsolutePath() + " exists:" + file.exists());
            LOG.info(LOGTAG + " uploading...");
            s3.putObject(new PutObjectRequest(bucketName, keyName, file));
            LOG.info(LOGTAG + file.getAbsolutePath() + " successfully uploaded");
            s3.setObjectAcl(bucketName, keyName, CannedAccessControlList.BucketOwnerFullControl);
        } catch (Throwable e) {
            LOG.error(e);
        }
    }
    public static void main(String[] args) throws IOException {
        new UploadToS3(null).execute("MobileAppReviewApprovalStat2.jpg", "MobileAppReviewApprovalStat2.jpg");
    }
}