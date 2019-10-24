
package ws.cache3;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlElementDecl;
import javax.xml.bind.annotation.XmlRegistry;
import javax.xml.namespace.QName;


/**
 * This object contains factory methods for each 
 * Java content interface and Java element interface 
 * generated in the ws.cache3 package. 
 * <p>An ObjectFactory allows you to programatically 
 * construct new instances of the Java representation 
 * for XML content. The Java representation of XML 
 * content can consist of schema derived interfaces 
 * and classes representing the binding of schema 
 * type definitions, element declarations and model 
 * groups.  Factory methods for each of these are 
 * provided in this class.
 * 
 */
@XmlRegistry
public class ObjectFactory {

    private final static QName _Put_QNAME = new QName("http://cache.ts.hadoop/", "put");
    private final static QName _Get_QNAME = new QName("http://cache.ts.hadoop/", "get");
    private final static QName _AddResponse_QNAME = new QName("http://cache.ts.hadoop/", "addResponse");
    private final static QName _GetResponse_QNAME = new QName("http://cache.ts.hadoop/", "getResponse");
    private final static QName _HasKeyResponse_QNAME = new QName("http://cache.ts.hadoop/", "hasKeyResponse");
    private final static QName _InitResponse_QNAME = new QName("http://cache.ts.hadoop/", "initResponse");
    private final static QName _PutResponse_QNAME = new QName("http://cache.ts.hadoop/", "putResponse");
    private final static QName _HasKey_QNAME = new QName("http://cache.ts.hadoop/", "hasKey");
    private final static QName _Init_QNAME = new QName("http://cache.ts.hadoop/", "init");
    private final static QName _Add_QNAME = new QName("http://cache.ts.hadoop/", "add");

    /**
     * Create a new ObjectFactory that can be used to create new instances of schema derived classes for package: ws.cache3
     * 
     */
    public ObjectFactory() {
    }

    /**
     * Create an instance of {@link Add }
     * 
     */
    public Add createAdd() {
        return new Add();
    }

    /**
     * Create an instance of {@link HasKey }
     * 
     */
    public HasKey createHasKey() {
        return new HasKey();
    }

    /**
     * Create an instance of {@link Init }
     * 
     */
    public Init createInit() {
        return new Init();
    }

    /**
     * Create an instance of {@link PutResponse }
     * 
     */
    public PutResponse createPutResponse() {
        return new PutResponse();
    }

    /**
     * Create an instance of {@link InitResponse }
     * 
     */
    public InitResponse createInitResponse() {
        return new InitResponse();
    }

    /**
     * Create an instance of {@link AddResponse }
     * 
     */
    public AddResponse createAddResponse() {
        return new AddResponse();
    }

    /**
     * Create an instance of {@link GetResponse }
     * 
     */
    public GetResponse createGetResponse() {
        return new GetResponse();
    }

    /**
     * Create an instance of {@link HasKeyResponse }
     * 
     */
    public HasKeyResponse createHasKeyResponse() {
        return new HasKeyResponse();
    }

    /**
     * Create an instance of {@link Get }
     * 
     */
    public Get createGet() {
        return new Get();
    }

    /**
     * Create an instance of {@link Put }
     * 
     */
    public Put createPut() {
        return new Put();
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Put }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "put")
    public JAXBElement<Put> createPut(Put value) {
        return new JAXBElement<Put>(_Put_QNAME, Put.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Get }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "get")
    public JAXBElement<Get> createGet(Get value) {
        return new JAXBElement<Get>(_Get_QNAME, Get.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link AddResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "addResponse")
    public JAXBElement<AddResponse> createAddResponse(AddResponse value) {
        return new JAXBElement<AddResponse>(_AddResponse_QNAME, AddResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link GetResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "getResponse")
    public JAXBElement<GetResponse> createGetResponse(GetResponse value) {
        return new JAXBElement<GetResponse>(_GetResponse_QNAME, GetResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link HasKeyResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "hasKeyResponse")
    public JAXBElement<HasKeyResponse> createHasKeyResponse(HasKeyResponse value) {
        return new JAXBElement<HasKeyResponse>(_HasKeyResponse_QNAME, HasKeyResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link InitResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "initResponse")
    public JAXBElement<InitResponse> createInitResponse(InitResponse value) {
        return new JAXBElement<InitResponse>(_InitResponse_QNAME, InitResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link PutResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "putResponse")
    public JAXBElement<PutResponse> createPutResponse(PutResponse value) {
        return new JAXBElement<PutResponse>(_PutResponse_QNAME, PutResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link HasKey }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "hasKey")
    public JAXBElement<HasKey> createHasKey(HasKey value) {
        return new JAXBElement<HasKey>(_HasKey_QNAME, HasKey.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Init }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "init")
    public JAXBElement<Init> createInit(Init value) {
        return new JAXBElement<Init>(_Init_QNAME, Init.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Add }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://cache.ts.hadoop/", name = "add")
    public JAXBElement<Add> createAdd(Add value) {
        return new JAXBElement<Add>(_Add_QNAME, Add.class, null, value);
    }

}
