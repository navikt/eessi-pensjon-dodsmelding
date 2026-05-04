import no.nav.security.token.support.spring.test.EnableMockOAuth2Server
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

//@SpringBootTest(classes = [RestTemplateConfig::class, UnsecuredWebMvcTestLauncher::class, EessiApplicationConfigTest.KafkaConfig::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(profiles = ["excludeKodeverk","unsecured-webmvctest"])
@DirtiesContext
@EnableMockOAuth2Server
class EessiApplicationConfigTest {

    @Test
    fun `contextTest`(){
        //alt er vel om vi kommer hit
    }
}
