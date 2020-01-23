Insert into ACCOUNT (ACCOUNT_ID,ACCOUNT_NAME,COMPLIANCE_CLASS,PASSWORD_LOCATION,ACCOUNT_OWNER_ID,FINANCIAL_ACCOUNT_NUMBER,CREATE_USER,CREATE_DATETIME,LAST_UPDATE_USER,LAST_UPDATE_DATETIME) values ('828157132492','Rhedcloud Dev 1','Standard','Steve Wheat''s head','P0934572','1521000000','swheat@rhedcloud.org',to_timestamp('2012-01-03 20:27:53.611489', 'YYYY-MM-DD HH24:MI'),'swheat',to_timestamp('2012-01-03 20:27:53.611489', 'YYYY-MM-DD HH24:MI'));
Insert into ACCOUNT (ACCOUNT_ID,ACCOUNT_NAME,COMPLIANCE_CLASS,PASSWORD_LOCATION,ACCOUNT_OWNER_ID,FINANCIAL_ACCOUNT_NUMBER,CREATE_USER,CREATE_DATETIME,LAST_UPDATE_USER,LAST_UPDATE_DATETIME) values ('533807676754','Rhedcloud Dev 2','Standard','Steve Wheat''s head','P0934572','1521000000','swheat@rhedcloud.org',to_timestamp('2012-01-03 20:27:53.611489', 'YYYY-MM-DD HH24:MI'),'swheat',to_timestamp('2012-01-03 20:27:53.611489', 'YYYY-MM-DD HH24:MI'));
Insert into ACCOUNT (ACCOUNT_ID,ACCOUNT_NAME,COMPLIANCE_CLASS,PASSWORD_LOCATION,ACCOUNT_OWNER_ID,FINANCIAL_ACCOUNT_NUMBER,CREATE_USER,CREATE_DATETIME,LAST_UPDATE_USER,LAST_UPDATE_DATETIME) values ('753445921657','Rhedcloud Dev 3','HIPAA','Steve Wheat''s head','P0934572','1521000000','swheat@rhedcloud.org',to_timestamp('2012-01-03 20:27:53.611489', 'YYYY-MM-DD HH24:MI'),'swheat',to_timestamp('2012-01-03 20:27:53.611489', 'YYYY-MM-DD HH24:MI'));

Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('533807676754','security','aws-security@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('533807676754','operations','aws-dev-2@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('533807676754','primary','aws-dev-2@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('828157132492','primary','aws-dev-1@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('828157132492','security','aws-security@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('828157132492','operations','aws-dev-1@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('753445921657','primary','aws-dev-3@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('753445921657','security','aws-security@rhedcloud.org');
Insert into ACCOUNT_EMAIL_ADDRESS (ACCOUNT_ID,TYPE_,EMAIL) values ('753445921657','operations','aws-dev-3@rhedcloud.org');
