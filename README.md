# CNT4007C

# Running

Run 'mvn package' to build the jar. Then, run 'java -jar ../target/project-1.0-SNAPSHOT.jar 1001' from project_root to test.

Recommendation: Construct a .bat (or equivalent for your OS) that has the following flow

cd "C:\Users\user\Documents\Repositories\CNT4007C\project_root"
START java -jar ../target/project-1.0-SNAPSHOT.jar 1001 
START java -jar ../target/project-1.0-SNAPSHOT.jar 1002 
START java -jar ../target/project-1.0-SNAPSHOT.jar 1003
