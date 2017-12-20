# KafkaAvroSRClient 
To produce messages to a Kafka topic using avro schema as defined in hortonworks schema registry (the example is for a secure cluster)

1. Create a producer config file, with its contents like below. let's call it /tmp/producer.conf:
---

> acks=all  
> sasl.kerberos.service.name=kafka  
> zookeeper.set.acl=true  
> kafkastore.security.protocol=SASL_PLAINTEXT  
> kafkastore.sasl.mechanism=GSSAPI  
> kafkastore.sasl.kerberos.service.name=kafka  
> security.protocol=SASL_PLAINTEXT  
> schema.registry.url=http://hdf-node3.hwx-labs.com:7788/api/v1  
> bootstrap.servers=hdf-node3.hwx-labs.com:6667  
> topic=packettrace  
> ignore.invalid.messages=false  
> compression.type=none  

-------
2. Prepare the data in json format. For example:  packettrace.json


> {"src":"10.0.6.3","dest":"stark","destport": 12345 }
> {"src":"wolverine","dest":"stark","destport": 2245 }

-------

3. Prepare the schema. Let's call it packettrace.avsc
>  {
>    "type" : "record",
>    "name" : "packettrace",
>    "namespace" : "hortonworks.hdp.support.packettrace",
>    "fields" : [
>       { "name" : "src" , "type" : "string" },
>       { "name" : "dest" , "type" : "string" },
>       { "name" : "destport" , "type" : "int" }
>    ]
> }

-------
4. Register the new schema to Schema registry with the same name as that of topic.

-------
5. The pom.xml is defined to use shaded plugin. Run the program as follows:  


	# java /tmp -Djava.security.auth.login.config=/etc/kafka/conf/kafka_client_jaas.conf -jar /tmp/KafkaAvro-0.0.2-SNAPSHOT.jar com.hwxlabs.registry.KafkaAvro.KafkaAvroSRClient -sm -p /tmp/producer.properties -d /tmp/data.avro_json -s /tmp/packettrace.avsc

This is a bit modified version of https://github.com/hortonworks/registry/blob/HDF-2.1.0.0/examples/schema-registry/avro/src/main/java/com/hortonworks/registries/schemaregistry/examples/avro/TruckEventsKafkaAvroSerDesApp.java
