package com.company.nopar.service;

import com.company.nopar.dto.AnnouncementRequest;
import com.company.nopar.dto.AnnouncementUpdateRequest;
import com.company.nopar.dto.BoardAnnouncementResponse;
import com.company.nopar.entity.Announcement;
import com.company.nopar.entity.AnnouncementRead;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.AnnouncementReadRepository;
import com.company.nopar.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tablón de avisos: el administrador los publica y los fija; los usuarios los leen y los marcan como leídos. */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementReadRepository readRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Publica el aviso. Salvo que se pida en silencio (notifyDrivers a false), se publica un evento: cuando la
     * transacción se confirma, los conductores reciben la notificación push. Si el envío falla el aviso ya está
     * publicado: la notificación es un extra y nunca bloquea ni deshace la publicación.
     */
    @Transactional
    public Announcement createAnnouncement(AnnouncementRequest request, User actor) {
        Announcement announcement = new Announcement();
        announcement.setTitle(request.title().strip());
        announcement.setBody(request.body().strip());
        announcement.setPinned(Boolean.TRUE.equals(request.pinned()));
        announcement.setCreatedBy(actor);
        Announcement saved = announcementRepository.save(announcement);
        if (request.shouldNotify()) {
            eventPublisher.publishEvent(new AnnouncementPublishedEvent(saved.getId(), saved.getTitle(), saved.getBody()));
        }
        return saved;
    }

    /** Solo cambia los campos enviados. */
    @Transactional
    public Announcement updateAnnouncement(Long id, AnnouncementUpdateRequest request) {
        if (request.isEmpty()) {
            throw new InvalidRequestException("No se ha indicado ningún cambio");
        }
        Announcement announcement = announcementRepository.findWithAuthorById(id)
                .orElseThrow(() -> notFound(id));

        if (request.title() != null) {
            announcement.setTitle(requireText(request.title(), "El título no puede estar vacío"));
        }
        if (request.body() != null) {
            announcement.setBody(requireText(request.body(), "El texto no puede estar vacío"));
        }
        if (request.pinned() != null) {
            announcement.setPinned(request.pinned());
        }
        announcement.setUpdatedAt(LocalDateTime.now());
        return announcementRepository.save(announcement);
    }

    /** Borra el aviso; sus lecturas se borran con él (ON DELETE CASCADE). */
    @Transactional
    public void deleteAnnouncement(Long id) {
        if (!announcementRepository.existsById(id)) {
            throw notFound(id);
        }
        announcementRepository.deleteById(id);
    }

    /**
     * Una página del tablón (primero los fijados, luego los más recientes), cada aviso con si 'reader' ya lo ha leído.
     * Las lecturas de toda la página se piden en una sola consulta, no una por aviso.
     */
    @Transactional(readOnly = true)
    public Page<BoardAnnouncementResponse> getBoard(User reader, int page, int size) {
        Page<Announcement> announcements = announcementRepository
                .findAllByOrderByPinnedDescCreatedAtDescIdDesc(PageRequest.of(page, size));

        List<Long> ids = announcements.getContent().stream().map(Announcement::getId).toList();
        Set<Long> readIds = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(readRepository.findReadAnnouncementIds(reader.getId(), ids));

        return announcements.map(a -> BoardAnnouncementResponse.from(a, readIds.contains(a.getId())));
    }

    /** Marca el aviso como leído por 'reader'. Repetirlo no hace nada: se conserva la primera fecha. */
    @Transactional
    public void markAsRead(Long announcementId, User reader) {
        if (!announcementRepository.existsById(announcementId)) {
            throw notFound(announcementId);
        }
        try {
            readRepository.markAsRead(announcementId, reader.getId(), LocalDateTime.now());
        } catch (DataIntegrityViolationException e) {
            // Lo borraron justo después de comprobar que existía: la clave foránea impide registrar la lectura
            throw notFound(announcementId);
        }
    }

    /** Quién ha leído el aviso y cuándo, por orden de lectura. */
    @Transactional(readOnly = true)
    public List<AnnouncementRead> getReaders(Long announcementId) {
        if (!announcementRepository.existsById(announcementId)) {
            throw notFound(announcementId);
        }
        return readRepository.findByAnnouncementWithUser(announcementId);
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Aviso no encontrado con ID: " + id);
    }

    private static String requireText(String value, String message) {
        String text = value.strip();
        if (text.isEmpty()) {
            throw new InvalidRequestException(message);
        }
        return text;
    }
}
