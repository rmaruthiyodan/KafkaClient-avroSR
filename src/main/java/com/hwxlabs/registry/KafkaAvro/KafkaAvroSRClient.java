package com.hwxlabs.registry.KafkaAvro;

import org.apache.commons.cli.OptionGroup;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.hortonworks.registries.schemaregistry.client.SchemaRegistryClient;
import com.hortonworks.registries.schemaregistry.serdes.avro.kafka.KafkaAvroDeserializer;
import com.hortonworks.registries.schemaregistry.serdes.avro.kafka.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;


import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.FileReader;

/**
 *
 */
public class KafkaAvroSRClient {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaAvroSRClient.class);

    public static final String MSGS_LIMIT = "msgsLimit";
    public static final String TOPIC = "topic";
    public static final String SCHEMA_REGISTRY_URL = "schema.registry.url";
    public static final String SECURITY_PROTOCOL = "security.protocol";
    public static final String ACKS = "acks";
    

    String producerProps;
    String schemaFile;
    String consumerProps;

    public KafkaAvroSRClient(String producerProps, String schemaFile) {
        this.producerProps = producerProps;
        this.schemaFile = schemaFile;
    }

    public KafkaAvroSRClient(String consumerProps) {
        this.consumerProps = consumerProps;
    }



    public void sendMessages(String payloadFile) throws Exception {
        Properties props = new Properties();
        props.load(new FileInputStream(this.producerProps));
        int limit = Integer.parseInt(props.getProperty(MSGS_LIMIT, 50 + ""));
        int current = 0;

        // convert json to avro records
        Schema schema = new Schema.Parser().parse(new FileInputStream(this.schemaFile));
        List<Object> avrorecords = new ArrayList<>();

        BufferedReader bufferedReader = new BufferedReader(new FileReader(payloadFile)) ;
            String line;
            while (current++ < limit && (line = bufferedReader.readLine()) != null) {
                avrorecords.add(jsonToAvro(line, schema));
            }    
     

        // send avro messages to given topic using KafkaAvroSerializer which registers payload schema if it does not exist
        // with schema name as "<topic-name>:v", type as "avro" and schemaGroup as "kafka".
        // schema registry should be running so that KafkaAvroSerializer can register the schema.
        Map<String, Object> producerConfig = createProducerConfig(props);

        String topicName = props.getProperty(TOPIC);
        produceMessage(topicName, avrorecords, producerConfig);
    }

    private Map<String, Object> createProducerConfig(Properties props) {
        String bootstrapServers = props.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.putAll(Collections.singletonMap(SchemaRegistryClient.Configuration.SCHEMA_REGISTRY_URL.name(), props.get(SCHEMA_REGISTRY_URL)));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG,props.get(ACKS));
        config.put("security.protocol", "SASL_PLAINTEXT");
        return config;
    }

    private Object jsonToAvro(String jsonString, Schema schema) throws Exception {
        DatumReader<Object> reader = new GenericDatumReader<Object>(schema);
        Object object = reader.read(null, DecoderFactory.get().jsonDecoder(schema, jsonString));

        if (schema.getType().equals(Schema.Type.STRING)) {
            object = object.toString();
        }
        return object;
    }

    private void produceMessage(String topicName, List<Object> msgs, Map<String, Object> producerConfig) {
        LOG.info("inside produce Message " + msgs.size());
        final Producer<String, Object> producer = new KafkaProducer<>(producerConfig);
        final Callback callback = new MyProducerCallback();
        for (Object msg : msgs) {
            LOG.info("Sending message: [{}] to topic: [{}]", msg, topicName);
            ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(topicName, msg);
            producer.send(producerRecord, callback);
        }
        producer.flush();
        LOG.info("Message successfully sent to topic: [{}]", topicName);
        producer.close(5, TimeUnit.SECONDS);
    }

    private static class MyProducerCallback implements Callback {
        @Override
        public void onCompletion(RecordMetadata recordMetadata, Exception e) {
            LOG.info("#### received [{}], ex: [{}]", recordMetadata, e);
        }
    }


    public void consumeMessages() throws Exception {
        Properties props = new Properties();
        props.load(new FileInputStream(this.consumerProps));
        String topicName = props.getProperty(TOPIC);
        Map<String, Object> consumerConfig = createConsumerConfig(props);

        KafkaConsumer consumer = new KafkaConsumer<>(consumerConfig);
        consumer.subscribe(Collections.singletonList(topicName));

        while(true) {
            ConsumerRecords<String, Object> records = consumer.poll(1000);
            LOG.info("records size " + records.count());
            for (ConsumerRecord<String, Object> record : records) {
                LOG.info("Received message: (" + record.key() + ", " + record.value() + ") at offset " + record.offset());
            }
        }
    }

    private Map<String, Object> createConsumerConfig(Properties props) {
        String bootstrapServers = props.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.putAll(Collections.singletonMap(SchemaRegistryClient.Configuration.SCHEMA_REGISTRY_URL.name(), props.get(SCHEMA_REGISTRY_URL)));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        if (props.containsKey(ConsumerConfig.GROUP_ID_CONFIG)) {
            config.put(ConsumerConfig.GROUP_ID_CONFIG, props.getProperty(ConsumerConfig.GROUP_ID_CONFIG));
        } else {
            config.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        }
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return config;
    }
    /**
     * Print the command line options help message and exit application.
     */
    @SuppressWarnings("static-access")
    private static void showHelpMessage(String[] args, Options options) {
        Options helpOptions = new Options();
        helpOptions.addOption(Option.builder("h").longOpt("help")
                .desc("print this message").build());
        try {
            CommandLine helpLine = new DefaultParser().parse(helpOptions, args, true);
            if (helpLine.hasOption("help") || args.length == 1) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("truck-events-kafka-ingest", options);
                System.exit(0);
            }
        } catch (ParseException ex) {
            LOG.error("Parsing failed.  Reason: " + ex.getMessage());
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {

        Option sendMessages = Option.builder("sm").longOpt("send-messages").desc("Send Messages to Kafka").type(Boolean.class).build();
        Option consumeMessages = Option.builder("cm").longOpt("consume-messages").desc("Consume Messages from Kafka").type(Boolean.class).build();


        Option dataFileOption = Option.builder("d").longOpt("data-file").hasArg().desc("Provide a data file").type(String.class).build();
        Option producerFileOption = Option.builder("p").longOpt("producer-config").hasArg().desc("Provide a Kafka producer config file").type(String.class).build();
        Option schemaOption = Option.builder("s").longOpt("schema-file").hasArg().desc("Provide a schema file").type(String.class).build();

        Option consumerFileOption = Option.builder("c").longOpt("consumer-config").hasArg().desc("Provide a Kafka Consumer config file").type(String.class).build();

        OptionGroup groupOpt = new OptionGroup();
        groupOpt.addOption(sendMessages);
        groupOpt.addOption(consumeMessages);
        groupOpt.setRequired(true);

        Options options = new Options();
        options.addOptionGroup(groupOpt);
        options.addOption(dataFileOption);
        options.addOption(producerFileOption);
        options.addOption(schemaOption);
        options.addOption(consumerFileOption);

        //showHelpMessage(args, options);

        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;

        try {
            commandLine = parser.parse(options, args);
            if (commandLine.hasOption("sm")) {
                if (commandLine.hasOption("p") && commandLine.hasOption("d") && commandLine.hasOption("s")) {
                    KafkaAvroSRClient kafkaavroclient = new KafkaAvroSRClient(commandLine.getOptionValue("p"),
                                                                                                                    commandLine.getOptionValue("s"));
                    kafkaavroclient.sendMessages(commandLine.getOptionValue("d"));
                } else {
                    LOG.error("please provide following options for sending messages to Kafka");
                    LOG.error("-d or --data-file");
                    LOG.error("-s or --schema-file");
                    LOG.error("-p or --producer-config");
                }
            } else if (commandLine.hasOption("cm")) {
                if (commandLine.hasOption("c")) {
                    KafkaAvroSRClient kafkaavroclient = new KafkaAvroSRClient(commandLine.getOptionValue("c"));
                    kafkaavroclient.consumeMessages();
                } else {
                    LOG.error("please provide following options for consuming messages from Kafka");
                    LOG.error("-c or --consumer-config");
                }
            }
        } catch(ParseException e) {
            LOG.error("Please provide all the options ", e);
        } catch(Exception e) {
            LOG.error("Failed to send/receive messages ", e);
        }

    }
}
// Command to run: /usr/jdk64/jdk1.8.0_112/bin/java -verbose -cp /usr/hdf/3.0.2.0-76/registry/libs/*:/tmp 
//-Djava.security.auth.login.config=/etc/kafka/conf/kafka_client_jaas.conf  
// -jar /tmp/KafkaAvro-0.0.2-SNAPSH^C.jar com.hwxlabs.registry.KafkaAvro.KafkaAvroSRClient
// -sm -p /tmp/producer.properties -d /tmp/data.avro_json -s /tmp/packettrace.avsc
