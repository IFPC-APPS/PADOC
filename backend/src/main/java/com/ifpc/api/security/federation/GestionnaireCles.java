package com.ifpc.api.security.federation;

import com.ifpc.api.models.CleSignature;
import com.ifpc.api.repositories.CleSignatureRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Détient les clés de signature des jetons fédérés et les publie au JWKS.
 *
 * <p><b>Pourquoi asymétrique, et pourquoi c'était bloquant.</b> Le reste de
 * l'application signe en HS256 avec un secret partagé entre le Core API et le
 * Calc Engine. En HS256, vérifier c'est signer : communiquer ce secret à une
 * plateforme fédérée pour qu'elle valide nos jetons lui donnerait le pouvoir
 * d'en forger — un {@code ROLE_ADMIN} au nom de n'importe qui, indistinguable
 * d'un jeton légitime. En RSA, IFPC garde la clé privée et ne publie que la
 * clé publique : les plateformes vérifient tout et n'émettent rien. C'est la
 * propriété qui rend la fédération possible (spec fédération §4).</p>
 *
 * <p>Le secret HS256 existant n'est pas touché : il reste interne au couple
 * Spring/FastAPI, qui sont dans la même frontière de confiance (spec §11).</p>
 */
@Component
@RequiredArgsConstructor
public class GestionnaireCles {

    private static final Logger log = LoggerFactory.getLogger(GestionnaireCles.class);

    /**
     * Délai pendant lequel une clé retirée reste publiée au JWKS.
     *
     * <p>Doit dépasser la durée de vie du plus long jeton signé avec elle, plus
     * la durée de cache du JWKS chez les clients. Sans ce recouvrement, une
     * rotation invalide d'un coup tous les jetons en circulation.</p>
     */
    private static final int JOURS_DE_RECOUVREMENT = 7;

    private final CleSignatureRepository depot;

    @PostConstruct
    @Transactional
    public void assurerUneCleActive() {
        if (depot.findAllByOrderByCreeeLeDesc().stream().anyMatch(CleSignature::estActive)) {
            return;
        }
        CleSignature nouvelle = genererEtEnregistrer();
        log.info("Fédération : aucune clé de signature en base, une paire RSA 2048 a été créée (kid={}).",
                nouvelle.getKid());
    }

    /**
     * Retire la clé active et en crée une nouvelle.
     *
     * <p>L'ancienne reste vérifiable {@value #JOURS_DE_RECOUVREMENT} jours :
     * les jetons déjà émis continuent d'être acceptés le temps d'expirer.</p>
     */
    @Transactional
    public CleSignature tourner() {
        LocalDateTime finDeValidite = LocalDateTime.now().plusDays(JOURS_DE_RECOUVREMENT);
        List<CleSignature> actives = depot.findAllByOrderByCreeeLeDesc().stream()
                .filter(CleSignature::estActive)
                .toList();
        for (CleSignature ancienne : actives) {
            ancienne.setRetireeLe(finDeValidite);
            depot.save(ancienne);
        }
        CleSignature nouvelle = genererEtEnregistrer();
        log.info("Fédération : rotation de clé. Nouvelle kid={}, {} ancienne(s) publiée(s) jusqu'au {}.",
                nouvelle.getKid(), actives.size(), finDeValidite);
        return nouvelle;
    }

    /**
     * Source de clés pour Spring Authorization Server.
     *
     * <p>Relit la base à chaque appel plutôt que de figer les clés au
     * démarrage : une rotation déclenchée sur une instance doit être vue par
     * les autres sans redémarrage. Le coût est une requête par émission de
     * jeton, négligeable devant la signature elle-même.</p>
     */
    public JWKSource<SecurityContext> sourceDeCles() {
        return (selecteur, contexte) -> selecteur.select(new JWKSet(clesPubliables()));
    }

    /**
     * Le {@code kid} de la clé qui signe, ou {@code null} s'il n'y en a aucune.
     *
     * <p>Indispensable dès qu'une rotation a eu lieu : plusieurs clés sont alors
     * publiées, et l'encodeur doit savoir laquelle employer — sans quoi il
     * refuse de signer (« multiple keys for the signing algorithm »).</p>
     */
    public String kidActif() {
        return depot.findAllByOrderByCreeeLeDesc().stream()
                .filter(CleSignature::estActive)
                .map(CleSignature::getKid)
                .findFirst()
                .orElse(null);
    }

    /** Les clés à publier au JWKS, celle qui signe en tête. */
    List<com.nimbusds.jose.jwk.JWK> clesPubliables() {
        return depot.findAllByOrderByCreeeLeDesc().stream()
                .filter(CleSignature::estPubliable)
                // Active d'abord : Nimbus retient la première candidate pour signer.
                .sorted(Comparator.comparing(CleSignature::estActive).reversed()
                        .thenComparing(CleSignature::getCreeeLe, Comparator.reverseOrder()))
                .map(GestionnaireCles::versJwk)
                .map(jwk -> (com.nimbusds.jose.jwk.JWK) jwk)
                .toList();
    }

    private CleSignature genererEtEnregistrer() {
        KeyPair paire = genererPaireRsa();
        CleSignature cle = CleSignature.builder()
                .kid(UUID.randomUUID().toString())
                .clePublique(Base64.getEncoder().encodeToString(paire.getPublic().getEncoded()))
                .clePrivee(Base64.getEncoder().encodeToString(paire.getPrivate().getEncoded()))
                .creeeLe(LocalDateTime.now())
                .build();
        return depot.save(cle);
    }

    private static KeyPair genererPaireRsa() {
        try {
            KeyPairGenerator generateur = KeyPairGenerator.getInstance("RSA");
            generateur.initialize(2048);
            return generateur.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de générer la paire de clés de signature", e);
        }
    }

    private static RSAKey versJwk(CleSignature cle) {
        try {
            KeyFactory fabrique = KeyFactory.getInstance("RSA");
            RSAPublicKey publique = (RSAPublicKey) fabrique.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(cle.getClePublique())));
            RSAPrivateKey privee = (RSAPrivateKey) fabrique.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(cle.getClePrivee())));
            return new RSAKey.Builder(publique)
                    .privateKey(privee)
                    .keyID(cle.getKid())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Clé de signature illisible : kid=" + cle.getKid(), e);
        }
    }
}
