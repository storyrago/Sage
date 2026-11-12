-- Debezium MySQL 커넥터 접속 계정. 권한 목록은 공식 문서의 요구사항 그대로다.
-- MySQL 8.0은 GRANT ... IDENTIFIED BY를 받지 않으므로 계정 생성과 권한 부여를 나눈다.
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'dbz';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
