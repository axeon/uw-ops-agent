
linux下要先执行脚本，获取配置信息。
java -agentlib:native-image-agent=config-output-dir=./ \
-jar uw-ops-agent-1.1.0-jar-with-dependencies.jar

当前的配置文件是dell R720在rocky linux 9下跑出来的。