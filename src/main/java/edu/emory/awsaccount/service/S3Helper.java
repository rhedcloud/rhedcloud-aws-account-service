package edu.emory.awsaccount.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;

public class S3Helper {
    String LOGTAG = "[S3Helper] ";
    final static Logger LOG = Logger.getLogger(S3Helper.class);
    private String bucketName = "emory-rhedcloud-aws-<env>-accountmetadata";
    private String accessKeyId = "***REMOVED***";
    private String secretKey = "";
    private File tempDir = new File("temp");

    public S3Helper(Properties properties) {
        tempDir.mkdir();
        if (properties != null) {
            accessKeyId = properties.getProperty("accessKeyId");
            secretKey = properties.getProperty("secretKey");
            bucketName = properties.getProperty("bucketName");
        }
    }
    public void uploadToS3(String keyName, String fileToUpload) {
        try {
            AmazonS3 amazonS3 = getS3();
            File file = new File(fileToUpload);
            LOG.info(LOGTAG + file.getAbsolutePath() + " exists:" + file.exists());
            LOG.info(LOGTAG + " uploading...");
            amazonS3.putObject(new PutObjectRequest(bucketName, keyName, file));
            LOG.info(LOGTAG + file.getAbsolutePath() + " successfully uploaded");
            amazonS3.setObjectAcl(bucketName, keyName, CannedAccessControlList.BucketOwnerFullControl);
        } catch (Throwable e) {
            LOG.error(LOGTAG+"bucketName="+bucketName+",accessKeyId="+accessKeyId,e);
        }
    }
    public static void main(String[] args) throws IOException {
        new S3Helper(null).uploadToS3("MobileAppReviewApprovalStat2.jpg", "MobileAppReviewApprovalStat2.jpg");
    }
    //private String accountStr = " <Account><AccountId>436693799073</AccountId><AccountName>Emory Dev 309</AccountName><ComplianceClass>Standard</ComplianceClass><PasswordLocation>AWS default</PasswordLocation><EmailAddress><Type>primary</Type><Email>aws-dev-309@emory.edu</Email></EmailAddress><EmailAddress><Type>operations</Type><Email>aws-dev-309@emory.edu</Email></EmailAddress><Property><Key>srdExempt</Key><Value>true</Value></Property><AccountOwnerId>P0934572</AccountOwnerId><FinancialAccountNumber>1521000000</FinancialAccountNumber><CreateUser>P0934572</CreateUser><CreateDatetime><Year>2019</Year><Month>2</Month><Day>13</Day><Hour>14</Hour><Minute>43</Minute><Second>47</Second><SubSecond>24</SubSecond><Timezone>America/New_York</Timezone></CreateDatetime><LastUpdateUser>P4883103</LastUpdateUser><LastUpdateDatetime><Year>2019</Year><Month>2</Month><Day>19</Day><Hour>14</Hour><Minute>49</Minute><Second>55</Second><SubSecond>80</SubSecond><Timezone>America/New_York</Timezone></LastUpdateDatetime></Account>";
    public List<String[]> readDeletedAccounts(String deletedAccountsFileName) {
        List<String[]> dataLines = new ArrayList<>();
        try {
            AmazonS3 amazonS3 = getS3();
            LOG.info(LOGTAG + " reading...");
            GetObjectRequest getOjectRequest = new GetObjectRequest(bucketName, deletedAccountsFileName);
            S3Object s3Object = amazonS3.getObject(getOjectRequest);
            InputStream inputStream = s3Object.getObjectContent();
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String line = br.readLine();
            while (line != null) {
                dataLines.add(line.split(","));
                line = br.readLine();
            }
            br.close();
        } catch (Throwable e) {
            LOG.error(LOGTAG+"bucketName="+bucketName+",deletedAccountsFileName="+deletedAccountsFileName+",accessKeyId="+accessKeyId,e);
        }
        return dataLines;
    }
    private AmazonS3 getS3() {
        BasicAWSCredentials awsCreds = new BasicAWSCredentials(accessKeyId, secretKey);
        AmazonS3 amazonS3 = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1)
                .withCredentials(new AWSStaticCredentialsProvider(awsCreds)).build();
        return amazonS3;
    }
    public void writeDeletedAccounts(List<String[]> deletedAccountDataLines, String deletedAccountsFileNameFull) throws IOException {
        toCsvFileAndUploadToS3(deletedAccountDataLines, deletedAccountsFileNameFull);
    }
    public void toCsvFileAndUploadToS3(List<String[]> dataLines, String fileName) throws IOException {
        FileUtils.cleanDirectory(tempDir);
        LOG.info("fileName=" + fileName);
        File csvOutputFile = new File(tempDir + "/" + fileName);
        try (PrintWriter pw = new PrintWriter(csvOutputFile)) {
            dataLines.stream().map(S3Helper::convertToCSV).forEach(pw::println);
            pw.close();
        }
        uploadToS3(fileName, csvOutputFile.getAbsolutePath());
    }
    private static String convertToCSV(String[] data) {
        return Stream.of(data).collect(Collectors.joining(","));
    }
}