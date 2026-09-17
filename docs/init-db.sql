-- Create isolated databases for each microservice
CREATE DATABASE video_db;
CREATE DATABASE interaction_db;
CREATE DATABASE feed_db;
CREATE DATABASE notification_db;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE video_db TO shortly_admin;
GRANT ALL PRIVILEGES ON DATABASE interaction_db TO shortly_admin;
GRANT ALL PRIVILEGES ON DATABASE feed_db TO shortly_admin;
GRANT ALL PRIVILEGES ON DATABASE notification_db TO shortly_admin;