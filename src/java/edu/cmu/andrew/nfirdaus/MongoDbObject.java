package edu.cmu.andrew.nfirdaus;

/*
 * @author Nanda Firdaus
 * Last Modified: November 8, 2017
 *
 * This class is used as a helper to do operation in mongodb.
 * Currently, it only implements insert method because this
 * project only requires insert operation.
 *
 */

import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.util.Arrays;
import java.util.HashMap;
import org.bson.Document;


public class MongoDbObject {
    
    private MongoClient mongo;
    private MongoDatabase db;
    private boolean auth;

    /**
     * Constrcutor of the class. Create a new mongodb connection to the server.
     * @param url Url of the mongodb database
     * @param port Port of the mongodb database
     * @param username Username of the mongodb database
     * @param password Password of the mongodb database
     * @param dbName Database name
     */
    public MongoDbObject(String url, int port, String username, String password, String dbName) {
        MongoCredential credential = MongoCredential.createCredential(username, dbName, password.toCharArray());
        MongoClient mongoClient = new MongoClient(new ServerAddress(), Arrays.asList(credential));
        mongo = new MongoClient(new ServerAddress(url, port),
                                             Arrays.asList(credential));
        
        db = mongo.getDatabase(dbName);
        
    }

    /**
     * Insert the data to database
     * @param collectionName
     * @param data
     */
    public void insert(String collectionName, HashMap<String, Object> data) {
        MongoCollection<Document> collection = db.getCollection(collectionName);

        // add all field to the insert query
        Document document = new Document();
        for (String key : data.keySet()) {
            document.append(key, data.get(key));
        }

        collection.insertOne(document);
    }
    
}
