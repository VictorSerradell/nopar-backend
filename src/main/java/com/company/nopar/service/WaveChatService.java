package com.company.nopar.service;

import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Wave;
import com.company.nopar.entity.WaveMessage;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.WaveChatReadRepository;
import com.company.nopar.repository.WaveMessageRepository;
import com.company.nopar.repository.WaveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * El chat de las olas: una conversación por ola y día. La leen y la escriben los conductores que ese día tienen turno
 * en la ola, los encargados y los administradores (estos dos, en cualquier ola). Cada mensaje lleva texto, una foto o
 * las dos cosas.
 *
 * Los conductores solo ven el chat de hoy y solo si hoy tienen turno en esa ola: quien no trabaja esa ola no lee lo
 * que se habla en ella, y al día siguiente el chat empieza de cero. Las fotos se guardan con {@link FileStorageService}
 * (que decide el tipo por el contenido, no por lo que diga el cliente).
 *
 * Al escribir se avisa por push a los demás miembros: los conductores de la ola y los encargados y administradores,
 * menos a quien escribe.
 *
 * Cada usuario tiene, por ola, un marcador con el último mensaje que ha leído: de ahí salen los mensajes sin leer
 * (los de otros, de hoy, posteriores al marcador).
 */
@Service
@RequiredArgsConstructor
public class WaveChatService {

    /** Lo máximo que cabe en un mensaje (en caracteres, no en bytes: un emoji cuenta como uno). */
    public static final int MAX_TEXT_CHARS = 1000;

    /** Cuántos mensajes se devuelven si la app no pide otra cantidad, y el máximo que se puede pedir. */
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;

    private final WaveRepository waveRepository;
    private final ShiftAssignmentRepository shiftRepository;
    private final WaveMessageRepository messageRepository;
    private final WaveChatReadRepository readRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Los mensajes del chat de hoy, por orden de llegada. Sin {@code afterId} devuelve los últimos {@code limit}
     * (lo que se ve al abrir el chat); con él, solo los posteriores a ese mensaje (lo que la app pide cada pocos
     * segundos para ponerse al día).
     *
     * @throws ResourceNotFoundException si la ola no existe
     * @throws AccessDeniedException     si es un conductor sin turno hoy en esa ola
     */
    @Transactional(readOnly = true)
    public List<WaveMessage> getMessages(Long waveId, Long afterId, int limit, User user) {
        findWave(waveId);
        LocalDate today = LocalDate.now();
        requireMember(waveId, today, user);

        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (afterId == null) {
            List<WaveMessage> latest = new ArrayList<>(
                    messageRepository.findLatest(waveId, today, PageRequest.of(0, size)));
            Collections.reverse(latest);
            return latest;
        }
        return messageRepository.findAfter(waveId, today, afterId, PageRequest.of(0, size));
    }

    /**
     * Escribe en el chat de hoy. El texto se recorta; sin texto hace falta una foto.
     *
     * @throws InvalidRequestException si el mensaje queda vacío o el texto pasa de {@link #MAX_TEXT_CHARS}
     * @throws com.company.nopar.exception.InvalidFileException si la foto no es una imagen JPEG o PNG
     * @throws ResourceNotFoundException si la ola no existe
     * @throws AccessDeniedException     si es un conductor sin turno hoy en esa ola
     */
    @Transactional
    public WaveMessage send(Long waveId, String text, MultipartFile photo, User sender) {
        Wave wave = findWave(waveId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = now.toLocalDate();
        requireMember(waveId, today, sender);

        String body = text == null || text.isBlank() ? null : text.strip();
        boolean hasPhoto = photo != null && !photo.isEmpty();
        if (body == null && !hasPhoto) {
            throw new InvalidRequestException("Escribe un mensaje o adjunta una foto");
        }
        if (body != null && body.codePointCount(0, body.length()) > MAX_TEXT_CHARS) {
            throw new InvalidRequestException("El mensaje no puede pasar de " + MAX_TEXT_CHARS + " caracteres");
        }

        String storedPhoto = hasPhoto ? fileStorage.storeFile(photo) : null;
        WaveMessage saved;
        try {
            WaveMessage message = new WaveMessage();
            message.setWaveId(waveId);
            message.setWorkDate(today);
            message.setSender(sender);
            message.setBody(body);
            message.setPhotoFile(storedPhoto);
            message.setSentAt(now);
            saved = messageRepository.save(message);
        } catch (RuntimeException e) {
            fileStorage.delete(storedPhoto); // no dejar en disco una foto de un mensaje que no se guardó
            throw e;
        }

        List<Long> recipients = recipientsOf(waveId, today, sender);
        if (!recipients.isEmpty()) {
            eventPublisher.publishEvent(new WaveChatMessageEvent(
                    saved.getId(), waveId, wave.getName(), today, sender.getUsername(), body, hasPhoto, recipients));
        }
        return saved;
    }

    /**
     * La foto de un mensaje. Los conductores solo la ven si hoy tienen turno en la ola y el mensaje es de hoy.
     *
     * @throws ResourceNotFoundException si la ola o el mensaje no existen, el mensaje es de otra ola o no lleva foto
     * @throws AccessDeniedException     si el usuario no puede ver ese chat
     */
    @Transactional(readOnly = true)
    public FileStorageService.StoredFile getPhoto(Long waveId, Long messageId, User user) {
        findWave(waveId);
        LocalDate today = LocalDate.now();
        requireMember(waveId, today, user);

        WaveMessage message = messageRepository.findByIdAndWaveId(messageId, waveId)
                .orElseThrow(() -> new ResourceNotFoundException("Mensaje no encontrado con ID: " + messageId));
        if (!isStaff(user) && !message.getWorkDate().equals(today)) {
            throw new AccessDeniedException("Solo se ven las fotos del chat de hoy");
        }
        if (!message.hasPhoto()) {
            throw new ResourceNotFoundException("El mensaje no lleva foto");
        }
        return fileStorage.load(message.getPhotoFile());
    }

    /**
     * Marca el chat de hoy como leído hasta el mensaje {@code lastReadId} (el más reciente que la app ha enseñado).
     * Solo avanza: marcar un mensaje anterior al ya leído no cambia nada, y un id mayor que el último mensaje real se
     * recorta a ese último (un error de la app no puede dejar sin avisar los mensajes que aún no existen).
     *
     * @throws ResourceNotFoundException si la ola no existe
     * @throws AccessDeniedException     si es un conductor sin turno hoy en esa ola
     */
    @Transactional
    public void markRead(Long waveId, Long lastReadId, User user) {
        findWave(waveId);
        LocalDate today = LocalDate.now();
        requireMember(waveId, today, user);

        Long newest = messageRepository.findNewestId(waveId, today);
        if (newest == null) {
            return; // hoy aún no se ha escrito nada: no hay nada que marcar
        }
        readRepository.markRead(user.getId(), waveId, Math.min(lastReadId, newest), LocalDateTime.now());
    }

    /**
     * Los mensajes sin leer de hoy por ola (id de la ola → cuántos); solo salen las olas que tienen alguno. Un
     * conductor solo cuenta la ola en la que trabaja hoy; encargados y administradores, todas.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> getUnread(User user) {
        LocalDate today = LocalDate.now();
        Map<Long, Long> unread = new LinkedHashMap<>();

        Long onlyWaveId = null;
        if (!isStaff(user)) {
            Optional<ShiftAssignment> shift = shiftRepository.findByWorkDateAndDriverId(today, user.getId());
            if (shift.isEmpty()) {
                return unread; // sin turno hoy no hay chat que leer
            }
            onlyWaveId = shift.get().getWave().getId();
        }

        for (Object[] row : readRepository.countUnread(user.getId(), today)) {
            Long waveId = ((Number) row[0]).longValue();
            long count = ((Number) row[1]).longValue();
            if (count > 0 && (onlyWaveId == null || onlyWaveId.equals(waveId))) {
                unread.put(waveId, count);
            }
        }
        return unread;
    }

    /**
     * Borra los mensajes de días anteriores a {@code cutoff} (de todas las olas) y las fotos que llevaban. Las fotos se
     * borran del disco una vez confirmada la transacción: si esta fallara, los mensajes seguirían ahí y no pueden
     * quedarse con la foto rota. Devuelve cuántos mensajes se han borrado.
     */
    @Transactional
    public int deleteMessagesBefore(LocalDate cutoff) {
        List<String> photos = messageRepository.findPhotoFilesBefore(cutoff);
        int deleted = messageRepository.deleteOlderThan(cutoff);
        deleteFilesAfterCommit(photos);
        return deleted;
    }

    private void deleteFilesAfterCommit(List<String> files) {
        if (files.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    files.forEach(fileStorage::delete);
                }
            });
        } else {
            files.forEach(fileStorage::delete);
        }
    }

    private Wave findWave(Long waveId) {
        return waveRepository.findById(waveId)
                .orElseThrow(() -> new ResourceNotFoundException("Ola no encontrada con ID: " + waveId));
    }

    /** Encargados y administradores entran al chat de cualquier ola; un conductor, solo al de la ola en la que trabaja hoy. */
    private void requireMember(Long waveId, LocalDate today, User user) {
        if (isStaff(user)) {
            return;
        }
        if (!shiftRepository.existsByWorkDateAndDriverIdAndWaveId(today, user.getId(), waveId)) {
            throw new AccessDeniedException("Hoy no tienes turno en esta ola");
        }
    }

    private static boolean isStaff(User user) {
        return user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER;
    }

    /** A quién se avisa: los conductores de la ola hoy y todos los encargados y administradores, salvo quien escribe. */
    private List<Long> recipientsOf(Long waveId, LocalDate today, User sender) {
        Set<Long> ids = new LinkedHashSet<>(shiftRepository.findDriverIdsByWorkDateAndWaveId(today, waveId));
        ids.addAll(userRepository.findIdsByRoleIn(List.of(User.Role.MANAGER, User.Role.ADMIN)));
        ids.remove(sender.getId());
        return List.copyOf(ids);
    }
}
