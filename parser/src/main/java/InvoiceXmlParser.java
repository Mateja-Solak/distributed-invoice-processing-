import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
/**
 * Parsira sirovi XML payload (bajtovi primljeni preko TCP-a od Watcher servisa) u
 * Invoice model. Ne baca provjerene izuzetke - poziva se iz ParserService.processInvoice
 * koji hvata bilo koji Exception i tretira ga kao "faktura se ne moze parsirati".
 *
 */
public final class InvoiceXmlParser {

    public static Invoice parse(byte[] xmlBytes) throws Exception {

        var factory= DocumentBuilderFactory.newInstance();
        var builder=factory.newDocumentBuilder();
        Document doc=builder.parse(new ByteArrayInputStream(xmlBytes));
        doc.getDocumentElement().normalize();

        Invoice invoice=new Invoice();
        invoice.id=text(doc.getDocumentElement(), "id");
        invoice.date=text(doc.getDocumentElement(), "date");
        invoice.currency=text(doc.getDocumentElement(), "currency");


        Element clientE1=(Element) doc.getElementsByTagName("client").item(0);
        ClientInfo client=new ClientInfo();
        if(clientE1!=null)
        {
            client.name=text(clientE1,"name");
            client.jib=text(clientE1,"jib");
            client.email=text(clientE1,"email");
        }
        invoice.client=client;


        List<Item> items=new ArrayList<>();
        NodeList itemNodes=doc.getElementsByTagName("item");
        for(int i=0;i<itemNodes.getLength();i++)
        {
            Element itemE1=(Element)itemNodes.item(i);
            Item item=new Item();
            item.description = text(itemE1, "description");
            item.quantity = parseDoubleSafe(text(itemE1, "quantity"));
            item.unitPrice = parseDoubleSafe(text(itemE1, "unitPrice"));
            items.add(item);
        }
        invoice.items = items;
        return invoice;



    }
    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0 || nodes.item(0).getTextContent() == null) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }
    private static double parseDoubleSafe(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private InvoiceXmlParser() {
    }
}
