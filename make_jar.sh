git clone https://github.com/gowthamraj07/LocalRepoServer.git
cd LocalRepoServer
git checkout tags/v1.0.0
./mvnw clean package
cp target/server-1.0.0.jar server-1.0.0.jar
rm -R target
rm -R directory
rm derby.log