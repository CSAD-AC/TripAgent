package uno.zhuchen.agent.tools.ticket12306.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Ticket12306ServiceTest {

    @Test
    void extractTicketQueryPathFromInitPage() {
        String html = "<script>var CLeftTicketUrl = 'leftTicket/queryG';</script>";

        assertEquals("/otn/leftTicket/queryG", Ticket12306Service.extractTicketQueryPath(html));
    }

    @Test
    void extractTicketQueryPathAcceptsDoubleQuotesAndLeadingSlash() {
        String html = "<script>var CLeftTicketUrl = \"/leftTicket/queryZ\";</script>";

        assertEquals("/otn/leftTicket/queryZ", Ticket12306Service.extractTicketQueryPath(html));
    }

    @Test
    void extractTicketQueryPathReturnsNullWhenMissing() {
        assertNull(Ticket12306Service.extractTicketQueryPath("<html></html>"));
    }
}
